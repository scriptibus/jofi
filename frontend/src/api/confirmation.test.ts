// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { HttpResponse, http } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import {
  CONFIRMATION_HEADER,
  CONFIRMATION_INVALID,
  CONFIRMATION_REQUIRED,
  ConfirmationMismatchError,
  confirmationRequest,
  matchesExpectation,
  runConfirmed,
} from "./confirmation";
import { ApiProblemError, apiFetch } from "./fetcher";

const url = new URL("/api/test/things/42", window.location.origin).href;
const server = setupServer();
const problemJson = { "Content-Type": "application/problem+json" };
const effect = { kind: "thing", name: "Thing 42", counts: { parts: 2 } };
const expected = { operation: "things.delete", targets: ["42"] };

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function required(operation = "things.delete", targets = ["42"]) {
  return {
    type: CONFIRMATION_REQUIRED,
    status: 428,
    confirmationToken: "t0k3n",
    expiresAt: "2026-09-30T10:05:00Z",
    operation,
    targets,
    effect,
  };
}

/** The backend's convention: 428 with a token first, 204 with the right token, 412 otherwise. */
function twoStepDelete(deleted: string[], firstAnswer = required()) {
  server.use(
    http.delete(url, ({ request }) => {
      const token = request.headers.get(CONFIRMATION_HEADER);
      if (token === null) return HttpResponse.json(firstAnswer, { status: 428, headers: problemJson });
      if (token !== "t0k3n") {
        return HttpResponse.json(
          { type: CONFIRMATION_INVALID, status: 412 },
          { status: 412, headers: problemJson },
        );
      }
      deleted.push("42");
      return new HttpResponse(null, { status: 204 });
    }),
  );
}

const deleteThing = (options?: RequestInit) =>
  apiFetch<void>("/api/test/things/42", { method: "DELETE", ...options });

describe("runConfirmed", () => {
  it("shows the server's effect and repeats the call with the token once the user agrees", async () => {
    const deleted: string[] = [];
    twoStepDelete(deleted);
    const ask = vi.fn().mockResolvedValue(true);

    const outcome = await runConfirmed(deleteThing, expected, ask);

    expect(outcome).toEqual({ status: "done", value: undefined });
    expect(ask).toHaveBeenCalledWith(expect.objectContaining({ operation: "things.delete", effect }));
    expect(deleted).toEqual(["42"]);
  });

  it("runs nothing more when the user declines", async () => {
    const deleted: string[] = [];
    twoStepDelete(deleted);

    const outcome = await runConfirmed(deleteThing, expected, async () => false);

    expect(outcome).toEqual({ status: "cancelled" });
    expect(deleted).toEqual([]);
  });

  it("refuses without asking when the server wants to confirm another operation or target", async () => {
    for (const answer of [required("things.purge"), required("things.delete", ["42", "43"])]) {
      const deleted: string[] = [];
      twoStepDelete(deleted, answer);
      const ask = vi.fn().mockResolvedValue(true);

      await expect(runConfirmed(deleteThing, expected, ask)).rejects.toBeInstanceOf(
        ConfirmationMismatchError,
      );
      expect(ask).not.toHaveBeenCalled();
      expect(deleted).toEqual([]);
    }
  });

  it("passes through a call that needs no confirmation without asking", async () => {
    server.use(http.delete(url, () => HttpResponse.json({ ok: true })));
    const ask = vi.fn();

    const outcome = await runConfirmed(
      (options) => apiFetch<{ ok: boolean }>(url, { method: "DELETE", ...options }),
      expected,
      ask,
    );

    expect(outcome).toEqual({ status: "done", value: { ok: true } });
    expect(ask).not.toHaveBeenCalled();
  });

  it("throws other errors, including a refused token, as ApiProblemError", async () => {
    server.use(
      http.delete(url, () =>
        HttpResponse.json({ type: CONFIRMATION_INVALID, status: 412 }, { status: 412, headers: problemJson }),
      ),
    );

    await expect(runConfirmed(deleteThing, expected, async () => true)).rejects.toBeInstanceOf(
      ApiProblemError,
    );
  });
});

describe("matchesExpectation", () => {
  it("compares the operation and the set of targets", () => {
    expect(
      matchesExpectation(
        { operation: "a", targets: ["2", "1", "1"] },
        { operation: "a", targets: ["1", "2"] },
      ),
    ).toBe(true);
    expect(matchesExpectation({ operation: "a", targets: ["1"] }, { operation: "b", targets: ["1"] })).toBe(
      false,
    );
    expect(
      matchesExpectation({ operation: "a", targets: ["1", "2"] }, { operation: "a", targets: ["1"] }),
    ).toBe(false);
  });
});

describe("confirmationRequest", () => {
  it("recognises only a complete 428 confirmation-required problem", () => {
    expect(confirmationRequest(new ApiProblemError(428, required()))).toEqual(required());
    expect(
      confirmationRequest(new ApiProblemError(428, { type: "urn:x:other", status: 428 })),
    ).toBeUndefined();
    expect(confirmationRequest(new ApiProblemError(428, { type: CONFIRMATION_REQUIRED }))).toBeUndefined();
    const { effect: _dropped, ...withoutEffect } = required();
    expect(confirmationRequest(new ApiProblemError(428, withoutEffect))).toBeUndefined();
    expect(confirmationRequest(new ApiProblemError(409, required()))).toBeUndefined();
    expect(confirmationRequest(new Error("offline"))).toBeUndefined();
  });
});
