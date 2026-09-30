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
import { FirstRunPage } from "./auth/FirstRunPage";
import { LoginPage, type LoginReason } from "./auth/LoginPage";
import { authState, refreshSession, safeRedirect, sessionQueryOptions } from "./auth/session";
import type { NoticeStore } from "./notices";
import { PlaceholderPage } from "./pages/PlaceholderPage";
import { SettingsPage } from "./pages/SettingsPage";
import { parseSharedContent, SharePage } from "./pages/SharePage";
import { NotFoundPage, PendingPage, RouteErrorPage } from "./pages/StatusPages";
import { AppShell } from "./shell/AppShell";

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

const dashboardRoute = placeholder("/", m.dashboard_heading, m.dashboard_empty, m.dashboard_eyebrow);
const applicationsRoute = placeholder("applications", m.nav_applications, m.applications_empty);
const companiesRoute = placeholder("companies", m.nav_companies, m.companies_empty);
const tasksRoute = placeholder("tasks", m.nav_tasks, m.tasks_empty);
const chatRoute = placeholder("chat", m.nav_chat, m.chat_empty);

const settingsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "settings",
  component: SettingsPage,
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
    companiesRoute,
    tasksRoute,
    chatRoute,
    settingsRoute,
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
