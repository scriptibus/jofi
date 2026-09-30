// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { QueryClient } from "@tanstack/react-query";
import {
  type AuthSessionResponse,
  getGetAuthSessionQueryKey,
  getGetAuthSessionQueryOptions,
} from "../../api/generated/jofi";

/** Where the user belongs: choosing the first password, logging in, or the app itself. */
export type AuthState = "first-run" | "login" | "app";

export function authState(session: AuthSessionResponse): AuthState {
  if (!session.setUp) return "first-run";
  return session.authenticated ? "app" : "login";
}

/**
 * The session status (`GET /api/auth/session`, which also hands out the CSRF cookie). Cached for
 * good: it changes only through login, logout, first run, or a 401, and each of those updates it.
 * The route guard reports its errors, so the global notices skip it.
 */
export function sessionQueryOptions() {
  return getGetAuthSessionQueryOptions({
    query: { staleTime: Number.POSITIVE_INFINITY, meta: { errorHandledLocally: true } },
  });
}

/** Asks the server again, for a fresh status and a fresh CSRF cookie (after login, logout, …). */
export function refreshSession(queryClient: QueryClient): Promise<AuthSessionResponse> {
  return queryClient.fetchQuery({ ...sessionQueryOptions(), staleTime: 0 });
}

/** Marks the session as ended at once (so guards redirect), then asks the server for the truth. */
export function markLoggedOut(queryClient: QueryClient): void {
  queryClient.setQueryData<AuthSessionResponse>(getGetAuthSessionQueryKey(), (session) =>
    session ? { ...session, authenticated: false } : session,
  );
  // Personal data of the ended session must not outlive it in memory.
  queryClient.removeQueries({
    predicate: (query) => query.queryKey[0] !== getGetAuthSessionQueryKey()[0],
  });
  void refreshSession(queryClient).catch(() => undefined);
}

/**
 * The `redirect` search parameter after login, if it is a path inside this app. Anything else
 * (another origin, `//host`, `javascript:`) is dropped, so a crafted login link cannot send the user
 * elsewhere.
 */
export function safeRedirect(value: unknown): string | undefined {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//")) return undefined;
  if (value.includes("\\")) return undefined;
  try {
    const url = new URL(value, window.location.origin);
    if (url.origin !== window.location.origin) return undefined;
    if (url.pathname === "/login" || url.pathname === "/first-run") return undefined;
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return undefined;
  }
}
