// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the backup API (ADR-0042) as MSW handlers, next to `fakeAuthBackend`,
// whose state (session, password, backoff) it shares. It mirrors the backend's status codes and problem
// types; the real flow runs in the e2e suite (tests/backup).

import { HttpResponse, http } from "msw";
import type { StagedBackupResponse } from "../api/generated/jofi";
import type { FakeAuthState } from "./fakeAuthBackend";

const origin = () => window.location.origin;
const problem = (status: number, code: string, extra: Record<string, unknown> = {}, headers = {}) =>
  HttpResponse.json(
    { type: `urn:jofi:problem:system:${code}`, title: "Problem", status, detail: code, ...extra },
    { status, headers: { "Content-Type": "application/problem+json", ...headers } },
  );

export const STAGED_ID = "7a1c3e9d-2b4f-4c1a-9e8d-0f1e2d3c4b5a";
export const BACKUP_FILE_NAME = "jofi-backup-20260930-120000.zip";
const TOKEN = "restore-t0k3n";

export interface FakeBackupState {
  /** Answers every backup call with 409 `backup-busy`. */
  busy?: boolean;
  /** An upload whose content is exactly this string is refused with this `reason` (413 for the limits). */
  refuse?: { content: string; reason: string };
  /** The restore's second step fails with this problem code and status. */
  restoreFails?: { status: number; code: string };
  /** Restore calls seen: `first` without token, `confirmed` with it. */
  restoreCalls: ("first" | "confirmed")[];
  staged: StagedBackupResponse;
}

export function fakeBackupBackend(auth: FakeAuthState, initial: Partial<FakeBackupState> = {}) {
  const state: FakeBackupState = {
    restoreCalls: [],
    staged: {
      id: STAGED_ID,
      createdAt: "2026-09-30T12:00:00Z",
      appVersion: "0.1.0",
      schemaVersion: "20260930064000",
      migratedFrom: null,
      rows: 1234,
      files: 5,
      includesKeyset: true,
    },
    ...initial,
  };

  /** Session, lock and password, in the backend's order. */
  const refusal = async (request: Request) => {
    if (!auth.authenticated || auth.sessionExpired) return problem(401, "not-logged-in");
    if (state.busy) return problem(409, "backup-busy");
    if (request.method === "POST" && request.headers.get("Content-Type") === "application/zip")
      return undefined;
    if (auth.throttleSeconds !== undefined)
      return problem(429, "login-throttled", {}, { "Retry-After": String(auth.throttleSeconds) });
    const body = (await request.clone().json()) as { password?: string };
    return body.password === auth.password ? undefined : problem(403, "invalid-credentials");
  };

  const handlers = [
    http.post(`${origin()}/api/system/backup/exports`, async ({ request }) => {
      const refused = await refusal(request);
      if (refused) return refused;
      return HttpResponse.arrayBuffer(new Uint8Array([0x50, 0x4b, 3, 4, 1, 2]).buffer, {
        headers: {
          "Content-Type": "application/zip",
          "Content-Disposition": `attachment; filename="${BACKUP_FILE_NAME}"`,
        },
      });
    }),
    http.post(`${origin()}/api/system/backup/restores`, async ({ request }) => {
      const refused = await refusal(request);
      if (refused) return refused;
      const content = await request.text();
      if (state.refuse && content === state.refuse.content) {
        const status = state.refuse.reason === "too-large" ? 413 : 422;
        return problem(status, "backup-refused", { reason: state.refuse.reason });
      }
      return HttpResponse.json(state.staged, { status: 201 });
    }),
    http.post(`${origin()}/api/system/backup/restores/:id`, async ({ request, params }) => {
      const refused = await refusal(request);
      if (refused) return refused;
      if (params.id !== state.staged.id) return problem(404, "backup-not-found");
      const token = request.headers.get("Jofi-Confirmation");
      if (token === null) {
        state.restoreCalls.push("first");
        return HttpResponse.json(
          {
            type: "urn:jofi:problem:shared:confirmation-required",
            status: 428,
            confirmationToken: TOKEN,
            expiresAt: "2026-09-30T12:05:00Z",
            operation: "system.backup.restore",
            targets: [state.staged.id],
            effect: {
              kind: "backup",
              name: state.staged.createdAt,
              counts: { rows: state.staged.rows, files: state.staged.files, keyset: 1 },
            },
          },
          { status: 428, headers: { "Content-Type": "application/problem+json" } },
        );
      }
      state.restoreCalls.push("confirmed");
      if (token !== TOKEN)
        return problem(412, "confirmation-invalid", { type: "urn:jofi:problem:shared:confirmation-invalid" });
      if (state.restoreFails) return problem(state.restoreFails.status, state.restoreFails.code);
      auth.authenticated = false;
      return new HttpResponse(null, { status: 204 });
    }),
  ];

  return { state, handlers };
}
