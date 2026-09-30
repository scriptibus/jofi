// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { ApiProblemError } from "../api/fetcher";
import { createNoticeStore } from "./notices";
import { describeError, isSessionEnded, ProblemType, throttledFor } from "./problems";
import { createAppQueryClient, shouldRetry } from "./queryClient";

const problem = (status: number, type?: string, detail?: string, retryAfter?: number) =>
  new ApiProblemError(
    status,
    { status, ...(type ? { type } : {}), ...(detail ? { detail } : {}) },
    retryAfter,
  );

describe("describeError", () => {
  it("names known problem types in the user's language", () => {
    expect(describeError(problem(403, ProblemType.csrf)).message).toBe(
      "Your security token expired. Reload the page and try again.",
    );
    expect(describeError(problem(503, ProblemType.unavailable)).message).toMatch(/temporarily unavailable/);
    expect(describeError(problem(412, ProblemType.confirmationInvalid)).message).toBe(
      "The confirmation expired or no longer matches. Please start again.",
    );
  });

  it("states the backoff wait from Retry-After", () => {
    expect(describeError(problem(429, ProblemType.throttled, undefined, 16)).message).toBe(
      "Too many failed attempts. Please wait 16 seconds and try again.",
    );
    expect(throttledFor(problem(429, ProblemType.throttled))).toBe(1);
    expect(throttledFor(problem(401, ProblemType.invalidCredentials))).toBeUndefined();
  });

  it("falls back to the status and keeps the server's detail for unknown problems", () => {
    expect(describeError(problem(409, "urn:x:other", "Version conflict"))).toEqual({
      message: "Jofi could not complete this request (HTTP 409). Please try again.",
      detail: "Version conflict",
    });
    expect(describeError(problem(502)).detail).toBeUndefined();
  });

  it("treats a failed fetch as offline", () => {
    expect(describeError(new TypeError("Failed to fetch")).message).toMatch(/cannot reach the server/);
  });

  it("recognises an ended session only by its problem type", () => {
    expect(isSessionEnded(problem(401, ProblemType.notLoggedIn))).toBe(true);
    expect(isSessionEnded(problem(401, ProblemType.invalidCredentials))).toBe(false);
  });
});

describe("app query client", () => {
  it("retries network failures and 5xx, never 4xx", () => {
    expect(shouldRetry(0, new TypeError("offline"))).toBe(true);
    expect(shouldRetry(0, problem(503))).toBe(true);
    expect(shouldRetry(2, problem(503))).toBe(false);
    expect(shouldRetry(0, problem(404))).toBe(false);
  });

  it("routes errors: 401 ends the session, local errors stay local, the rest become notices", async () => {
    const notices = createNoticeStore();
    const onSessionEnded = vi.fn();
    const client = createAppQueryClient({ notices, onSessionEnded });
    const fail = (error: Error, local = false) =>
      client
        .getMutationCache()
        .build(client, {
          mutationFn: () => Promise.reject(error),
          ...(local ? { meta: { errorHandledLocally: true } } : {}),
        })
        .execute(undefined)
        .catch(() => undefined);

    await fail(problem(401, ProblemType.notLoggedIn), true);
    expect(onSessionEnded).toHaveBeenCalledOnce();

    await fail(problem(409, "urn:x"), true);
    expect(notices.snapshot()).toEqual([]);

    await fail(problem(409, "urn:x"));
    await fail(problem(409, "urn:x"));
    expect(notices.snapshot()).toHaveLength(1);
  });
});

describe("notice store", () => {
  it("keeps the newest three, dismisses and clears", () => {
    const store = createNoticeStore();
    const listener = vi.fn();
    store.subscribe(listener);
    for (const message of ["a", "b", "c", "d"]) store.publish({ message });
    expect(store.snapshot().map((notice) => notice.message)).toEqual(["b", "c", "d"]);

    store.dismiss(store.snapshot()[0]?.id ?? -1);
    expect(store.snapshot().map((notice) => notice.message)).toEqual(["c", "d"]);
    store.clear();
    expect(store.snapshot()).toEqual([]);
    expect(listener).toHaveBeenCalledTimes(6);
  });
});
