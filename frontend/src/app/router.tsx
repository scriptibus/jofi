// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  Outlet,
  type ParsedLocation,
  type RouterHistory,
  redirect,
} from "@tanstack/react-router";
import { m } from "../paraglide/messages.js";
import { SetupWizard } from "./ai/SetupWizard";
import { parseSetupStep, type SetupStep, shouldOpenSetupGuide } from "./ai/setupGuide";
import { ApplicationDetailPage } from "./applications/ApplicationDetailPage";
import { ApplicationsPage } from "./applications/ApplicationsPage";
import { parseApplicationsSearch, parseNewApplicationSearch } from "./applications/applicationsSearch";
import { EditApplicationPage } from "./applications/EditApplicationPage";
import { NewApplicationPage } from "./applications/NewApplicationPage";
import { parseApplicationSearch } from "./applications/tabs";
import { FirstRunPage } from "./auth/FirstRunPage";
import { LoginPage, type LoginReason } from "./auth/LoginPage";
import { authState, refreshSession, safeRedirect, sessionQueryOptions } from "./auth/session";
import { CompaniesPage, parseCompaniesSearch } from "./companies/CompaniesPage";
import { CompanyDetailPage } from "./companies/CompanyDetailPage";
import { EditCompanyPage, NewCompanyPage } from "./companies/CompanyEditPages";
import { ContactDetailPage } from "./contacts/ContactDetailPage";
import { EditContactPage, NewContactPage } from "./contacts/ContactEditPages";
import { ContactsPage, parseContactsSearch, parseNewContactSearch } from "./contacts/ContactsPage";
import { DashboardPage } from "./dashboard/DashboardPage";
import type { NoticeStore } from "./notices";
import { PlaceholderPage } from "./pages/PlaceholderPage";
import { SettingsPage } from "./pages/SettingsPage";
import { parseSharedContent, SharePage } from "./pages/SharePage";
import { NotFoundPage, PendingPage, RouteErrorPage } from "./pages/StatusPages";
import { AppShell } from "./shell/AppShell";
import { EditTaskPage, NewTaskPage } from "./tasks/TaskEditPages";
import { TasksPage } from "./tasks/TasksPage";

export interface RouterContext {
  queryClient: QueryClient;
  notices: NoticeStore;
}

/** How old a cached "logged in" may be before navigation asks the server again (catches expiry). */
const SESSION_RECHECK_MS = 60_000;

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
  pendingComponent: PendingPage,
  errorComponent: RouteErrorPage,
  notFoundComponent: NotFoundPage,
});

// --- Public: first run and login -------------------------------------------------------------

interface LoginSearch {
  redirect?: string;
  reason?: LoginReason;
}

const firstRunRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "first-run",
  beforeLoad: async ({ context }) => {
    const state = authState(await refreshSession(context.queryClient));
    if (state === "login") throw redirect({ to: "/login" });
    if (state === "app") throw redirect({ to: "/" });
  },
  component: FirstRunPage,
});

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "login",
  validateSearch: (search: Record<string, unknown>): LoginSearch => {
    const result: LoginSearch = {};
    const target = safeRedirect(search.redirect);
    if (target) result.redirect = target;
    if (search.reason === "expired" || search.reason === "logged-out" || search.reason === "restored")
      result.reason = search.reason;
    return result;
  },
  beforeLoad: async ({ context, search }) => {
    // Always fresh: also renews the CSRF cookie the login request needs.
    const state = authState(await refreshSession(context.queryClient));
    if (state === "first-run") throw redirect({ to: "/first-run" });
    if (state === "app") throw redirect({ href: search.redirect ?? "/" });
  },
  component: LoginPage,
});

// --- Behind login: the shell and its pages ---------------------------------------------------

/** The auth guard: only a logged-in user gets past; everyone else goes to first run or login. */
export async function requireSession(queryClient: QueryClient, location: ParsedLocation) {
  const session = await queryClient.fetchQuery({ ...sessionQueryOptions(), staleTime: SESSION_RECHECK_MS });
  const state = authState(session);
  if (state === "first-run") throw redirect({ to: "/first-run" });
  if (state === "login") {
    const target = safeRedirect(location.href);
    throw redirect({ to: "/login", search: target && target !== "/" ? { redirect: target } : {} });
  }
}

const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "_app",
  beforeLoad: ({ context, location }) => requireSession(context.queryClient, location),
  component: AppShell,
});

function placeholder<const TPath extends string>(
  path: TPath,
  title: () => string,
  body: () => string,
  eyebrow?: () => string,
) {
  return createRoute({
    getParentRoute: () => appRoute,
    path,
    component: () => (
      <PlaceholderPage title={title()} {...(eyebrow ? { eyebrow: eyebrow() } : {})}>
        {body()}
      </PlaceholderPage>
    ),
  });
}

const dashboardRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/",
  // First login without any AI provider: the setup guide takes over until it is skipped (spec §3.2).
  beforeLoad: async ({ context }) => {
    if (await shouldOpenSetupGuide(context.queryClient))
      throw redirect({ to: "/setup", search: { step: "welcome" } });
  },
  component: DashboardPage,
});

// Applications (spec §6.3): the list with its filters, order and page in the URL; create (`?company=`
// preselects); the detail page with its tabs (`?tab=`) and the edit page.
const applicationsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "applications",
  validateSearch: parseApplicationsSearch,
  component: ApplicationsPage,
});

const newApplicationRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "applications/new",
  validateSearch: parseNewApplicationSearch,
  component: NewApplicationPage,
});

const applicationRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "applications/$applicationId",
  validateSearch: parseApplicationSearch,
  component: ApplicationDetailPage,
});

const editApplicationRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "applications/$applicationId/edit",
  component: EditApplicationPage,
});

// Tasks (spec §10.2): the grouped list with quick add, complete and delete; create and edit with all details.
const tasksRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "tasks",
  component: TasksPage,
});

const newTaskRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "tasks/new",
  component: NewTaskPage,
});

const editTaskRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "tasks/$taskId/edit",
  component: EditTaskPage,
});

const chatRoute = placeholder("chat", m.nav_chat, m.chat_empty);

// Companies (spec §5): list with search and filter, create, detail, edit.
const companiesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "companies",
  validateSearch: parseCompaniesSearch,
  component: CompaniesPage,
});

const newCompanyRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "companies/new",
  component: NewCompanyPage,
});

const companyRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "companies/$companyId",
  component: CompanyDetailPage,
});

const editCompanyRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "companies/$companyId/edit",
  component: EditCompanyPage,
});

// Contacts (spec §5): list with search and company filter, create, detail, edit. Only ids in URLs.
const contactsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "contacts",
  validateSearch: parseContactsSearch,
  component: ContactsPage,
});

const newContactRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "contacts/new",
  validateSearch: parseNewContactSearch,
  component: NewContactPage,
});

const contactRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "contacts/$contactId",
  component: ContactDetailPage,
});

const editContactRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "contacts/$contactId/edit",
  component: EditContactPage,
});

const settingsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "settings",
  component: SettingsPage,
});

const setupRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "setup",
  validateSearch: (search: Record<string, unknown>): { step: SetupStep } => ({
    step: parseSetupStep(search.step),
  }),
  component: function SetupPage() {
    const { step } = setupRoute.useSearch();
    return <SetupWizard step={step} />;
  },
});

/** Web Share Target (manifest `share_target`, GET): `/share?title=…&text=…&url=…`. */
const shareRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "share",
  validateSearch: parseSharedContent,
  component: SharePage,
});

const notFoundRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "$",
  component: NotFoundPage,
});

export const routeTree = rootRoute.addChildren([
  firstRunRoute,
  loginRoute,
  appRoute.addChildren([
    dashboardRoute,
    applicationsRoute,
    newApplicationRoute,
    applicationRoute,
    editApplicationRoute,
    companiesRoute,
    newCompanyRoute,
    companyRoute,
    editCompanyRoute,
    contactsRoute,
    newContactRoute,
    contactRoute,
    editContactRoute,
    tasksRoute,
    newTaskRoute,
    editTaskRoute,
    chatRoute,
    settingsRoute,
    setupRoute,
    shareRoute,
    notFoundRoute,
  ]),
]);

/** `history` defaults to the browser's; tests pass a memory history. */
export function createAppRouter(context: RouterContext, history?: RouterHistory) {
  return createRouter({
    routeTree,
    context,
    ...(history ? { history } : {}),
    // Route data lives in TanStack Query; the router itself caches nothing.
    defaultPreloadStaleTime: 0,
    defaultPendingMinMs: 0,
    scrollRestoration: true,
  });
}

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
