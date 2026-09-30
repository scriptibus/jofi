// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// API seed steps for the e2e stack (ADR-0036), run once per `pnpm e2e` by the `seed` setup project (seed.setup.ts). They use only
// Jofi's public API, like a user would: nothing in the app exists for e2e. Each step is idempotent, so a
// reused stack (E2E_REUSE_STACK=1) seeds cleanly. Data without an API yet is seeded by SQL (seed/db/).

import { fileURLToPath } from "node:url";
import type { APIRequestContext } from "@playwright/test";

/** Browser state (session and CSRF cookies) every test starts with. Git-ignored. */
export const E2E_STORAGE_STATE = fileURLToPath(
  new URL("../../../playwright/.auth/e2e.json", import.meta.url),
);

/** The e2e user's password: public on purpose, the stack only listens on 127.0.0.1. */
export const E2E_PASSWORD = "jofi-e2e-demo-password";

interface AuthSession {
  setUp: boolean;
  authenticated: boolean;
  setupTokenRequired: boolean;
}

/**
 * Creates the single user through first run (#16) if there is none yet, then logs in. The request
 * context keeps the session cookie for the storage state. Before login exists (no /api/auth/session),
 * every API call is open and there is nothing to do.
 */
export async function logInAsE2eUser(request: APIRequestContext): Promise<"logged-in" | "no-auth"> {
  const status = await request.get("/api/auth/session");
  if (status.status() === 404) return "no-auth";
  await expectOk(status, "GET /api/auth/session");
  const session = (await status.json()) as AuthSession;
  if (session.authenticated) return "logged-in";
  if (session.setupTokenRequired) {
    throw new Error(
      "First run wants a setup token: the e2e stack must bind to 127.0.0.1 (JOFI_BIND_ADDRESS).",
    );
  }
  const path = session.setUp ? "/api/auth/login" : "/api/auth/first-run";
  const response = await request.post(path, {
    data: { password: E2E_PASSWORD },
    headers: { "X-XSRF-TOKEN": await csrfToken(request) },
  });
  await expectOk(response, `POST ${path}`);
  return "logged-in";
}

/** Spring Security's SPA CSRF protection: echo the XSRF-TOKEN cookie in a header. */
async function csrfToken(request: APIRequestContext): Promise<string> {
  const { cookies } = await request.storageState();
  const token = cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value;
  if (token === undefined) throw new Error("GET /api/auth/session set no XSRF-TOKEN cookie");
  return token;
}

async function expectOk(
  response: { ok(): boolean; status(): number; text(): Promise<string> },
  what: string,
) {
  if (!response.ok()) throw new Error(`${what} answered ${response.status()}: ${await response.text()}`);
}
