// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the auth API (ADR-0035) as MSW handlers, for component and routing
// tests. The real backend is covered by the e2e suite; this mirrors its status codes and problem
// types so the UI's handling of each case can be tested quickly.

import { HttpResponse, http } from "msw";

const origin = () => window.location.origin;
const problem = (status: number, code: string, detail: string, headers: Record<string, string> = {}) =>
  HttpResponse.json(
    { type: `urn:jofi:problem:system:${code}`, title: "Problem", status, detail },
    { status, headers: { "Content-Type": "application/problem+json", ...headers } },
  );

export interface FakeAuthState {
  setUp: boolean;
  authenticated: boolean;
  password: string;
  setupToken: string;
  /** When set, every password check answers 429 with this `Retry-After`. */
  throttleSeconds?: number;
  /** When true, every call that needs a session answers 401 (an expired session). */
  sessionExpired?: boolean;
  /** When set, `GET /api/system/info` answers with this status as problem details. */
  systemInfoStatus?: number;
}

export function fakeAuthBackend(initial: Partial<FakeAuthState> = {}) {
  const state: FakeAuthState = {
    setUp: true,
    authenticated: false,
    password: "correct horse battery staple",
    setupToken: "the-setup-token",
    ...initial,
  };
  const throttled = () =>
    state.throttleSeconds === undefined
      ? undefined
      : problem(429, "login-throttled", "Too many failed attempts", {
          "Retry-After": String(state.throttleSeconds),
        });
  const needsSession = () =>
    state.authenticated && !state.sessionExpired ? undefined : problem(401, "not-logged-in", "Log in first");

  const handlers = [
    http.get(`${origin()}/api/auth/session`, () =>
      HttpResponse.json({
        setUp: state.setUp,
        authenticated: state.authenticated && !state.sessionExpired,
        setupTokenRequired: !state.setUp,
      }),
    ),
    http.post(`${origin()}/api/auth/first-run`, async ({ request }) => {
      if (state.setUp) return problem(404, "already-set-up", "First run is complete");
      const body = (await request.json()) as { password: string; setupToken?: string };
      if (body.setupToken !== state.setupToken) return problem(403, "invalid-setup-token", "Wrong token");
      if (body.password.length < 15) return problem(422, "weak-password", "Too short");
      Object.assign(state, { setUp: true, authenticated: true, password: body.password });
      return new HttpResponse(null, { status: 204 });
    }),
    http.post(`${origin()}/api/auth/login`, async ({ request }) => {
      const refusal = throttled();
      if (refusal) return refusal;
      const body = (await request.json()) as { password: string };
      if (body.password !== state.password) return problem(401, "invalid-credentials", "Wrong password");
      Object.assign(state, { authenticated: true, sessionExpired: false });
      return new HttpResponse(null, { status: 204 });
    }),
    http.post(`${origin()}/api/auth/logout`, () => {
      state.authenticated = false;
      return new HttpResponse(null, { status: 204 });
    }),
    http.put(`${origin()}/api/auth/password`, async ({ request }) => {
      const refusal = needsSession() ?? throttled();
      if (refusal) return refusal;
      const body = (await request.json()) as { currentPassword: string; newPassword: string };
      if (body.currentPassword !== state.password) return problem(403, "invalid-credentials", "Wrong");
      state.password = body.newPassword;
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(`${origin()}/api/system/info`, () => {
      const refusal = needsSession();
      if (refusal) return refusal;
      if (state.systemInfoStatus) return problem(state.systemInfoStatus, "test", "Database on fire");
      return HttpResponse.json({ name: "Jofi", version: "1.2.3" });
    }),
  ];

  return { state, handlers };
}
