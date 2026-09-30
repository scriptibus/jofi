// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Tests for the API seed step against a scripted stand-in of Jofi's auth API (#16's contract).
// Run: pnpm test:stack

import assert from "node:assert/strict";
import { describe, test } from "node:test";
import type { APIRequestContext } from "@playwright/test";
import { E2E_PASSWORD, logInAsE2eUser } from "./api.ts";

interface Call {
  method: string;
  path: string;
  data?: unknown;
  headers?: Record<string, string>;
}

function response(status: number, body: unknown = {}) {
  return { ok: () => status < 400, status: () => status, json: async () => body, text: async () => "" };
}

/** Answers GET /api/auth/session with [session] (or 404 when null) and every POST with 204. */
function fakeApi(session: object | null) {
  const calls: Call[] = [];
  const request = {
    get: async (path: string) => {
      calls.push({ method: "GET", path });
      return session === null ? response(404) : response(200, session);
    },
    post: async (path: string, options: { data: unknown; headers: Record<string, string> }) => {
      calls.push({ method: "POST", path, data: options.data, headers: options.headers });
      return response(204);
    },
    storageState: async () => ({ cookies: [{ name: "XSRF-TOKEN", value: "csrf" }], origins: [] }),
  };
  return { calls, request: request as unknown as APIRequestContext };
}

describe("logInAsE2eUser", () => {
  test("does nothing on a build without login", async () => {
    const { calls, request } = fakeApi(null);
    assert.equal(await logInAsE2eUser(request, ""), "no-auth");
    assert.equal(calls.length, 1);
  });

  test("first run sends the password and the setup token with the CSRF header", async () => {
    const { calls, request } = fakeApi({ setUp: false, authenticated: false, setupTokenRequired: true });
    assert.equal(await logInAsE2eUser(request, "token-from-volume"), "logged-in");
    assert.deepEqual(calls[1], {
      method: "POST",
      path: "/api/auth/first-run",
      data: { password: E2E_PASSWORD, setupToken: "token-from-volume" },
      headers: { "X-XSRF-TOKEN": "csrf" },
    });
  });

  test("fails loudly when first run needs a token and none was found", async () => {
    const { calls, request } = fakeApi({ setUp: false, authenticated: false, setupTokenRequired: true });
    await assert.rejects(logInAsE2eUser(request, ""), /JOFI_E2E_SETUP_TOKEN is empty/);
    assert.equal(calls.length, 1);
  });

  test("an instance that is set up gets a plain login, without a token", async () => {
    const { calls, request } = fakeApi({ setUp: true, authenticated: false, setupTokenRequired: false });
    assert.equal(await logInAsE2eUser(request, ""), "logged-in");
    assert.deepEqual(calls[1]?.path, "/api/auth/login");
    assert.deepEqual(calls[1]?.data, { password: E2E_PASSWORD });
  });
});
