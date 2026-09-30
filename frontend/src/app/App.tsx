// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type RouterHistory, RouterProvider } from "@tanstack/react-router";
import { markLoggedOut } from "./auth/session";
import { createNoticeStore } from "./notices";
import { createAppQueryClient } from "./queryClient";
import { createAppRouter } from "./router";

export interface AppInstance {
  queryClient: QueryClient;
  router: ReturnType<typeof createAppRouter>;
}

/**
 * Wires the QueryClient, the router and the global notices together. A 401 from any request
 * marks the session as ended and returns to the login screen, which comes back here afterwards.
 */
export function createApp(history?: RouterHistory): AppInstance {
  const notices = createNoticeStore();
  let router: ReturnType<typeof createAppRouter> | undefined;
  const queryClient = createAppQueryClient({
    notices,
    onSessionEnded: () => {
      markLoggedOut(queryClient);
      notices.clear();
      if (!router) return;
      const { pathname, href } = router.state.location;
      if (pathname === "/login" || pathname === "/first-run") return;
      void router.navigate({ to: "/login", search: { redirect: href, reason: "expired" } });
    },
  });
  router = createAppRouter({ queryClient, notices }, history);
  return { queryClient, router };
}

export function App({ app }: { app: AppInstance }) {
  return (
    <QueryClientProvider client={app.queryClient}>
      <RouterProvider router={app.router} />
    </QueryClientProvider>
  );
}
