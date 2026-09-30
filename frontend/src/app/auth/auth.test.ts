// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { QueryClient } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { getGetAuthSessionQueryKey } from "../../api/generated/jofi";
import { newPasswordError, repeatPasswordError } from "./password";
import { authState, markLoggedOut, safeRedirect } from "./session";
import { useThrottle } from "./useThrottle";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("authState", () => {
  it("puts first run before login before the app", () => {
    expect(authState({ setUp: false, authenticated: false, setupTokenRequired: true })).toBe("first-run");
    expect(authState({ setUp: true, authenticated: false, setupTokenRequired: false })).toBe("login");
    expect(authState({ setUp: true, authenticated: true, setupTokenRequired: false })).toBe("app");
  });
});

describe("safeRedirect", () => {
  it("keeps paths inside the app", () => {
    expect(safeRedirect("/applications?view=board#top")).toBe("/applications?view=board#top");
    expect(safeRedirect("/share?url=https%3A%2F%2Fexample.com")).toBe("/share?url=https%3A%2F%2Fexample.com");
  });

  it("drops other origins, schemes, protocol-relative and auth pages", () => {
    for (const value of [
      "https://evil.example/",
      "//evil.example/x",
      "/\\evil.example",
      // Dot segments that normalise to a protocol-relative "//evil.example".
      "/.//evil.example",
      "/%2e//evil.example",
      "/a/..//evil.example",
      "javascript:alert(1)",
      "/login",
      "/first-run?x=1",
      42,
      undefined,
    ]) {
      expect(safeRedirect(value)).toBeUndefined();
    }
  });
});

describe("password rules", () => {
  it("accepts 15 to 256 characters, counting like the backend", () => {
    expect(newPasswordError("a".repeat(14))).toBe("Use at least 15 characters.");
    expect(newPasswordError("a".repeat(15))).toBeNull();
    expect(newPasswordError("a".repeat(256))).toBeNull();
    expect(newPasswordError("a".repeat(257))).toBe("Use at most 256 characters.");
    // 15 emoji are 15 characters (code points) although they are 30 UTF-16 units.
    expect(newPasswordError("🐴".repeat(15))).toBeNull();
    expect(newPasswordError("🐴".repeat(14))).toBe("Use at least 15 characters.");
  });

  it("requires the repetition to match", () => {
    expect(repeatPasswordError("same", "same")).toBeNull();
    expect(repeatPasswordError("same", "other")).toBe("The passwords do not match.");
  });
});

describe("markLoggedOut", () => {
  it("ends the session at once, drops cached data and asks the server again", async () => {
    let fetched = 0;
    server.use(
      http.get(new URL("/api/auth/session", window.location.origin).href, () => {
        fetched++;
        return HttpResponse.json({ setUp: true, authenticated: false, setupTokenRequired: false });
      }),
    );
    const client = new QueryClient();
    client.setQueryData(getGetAuthSessionQueryKey(), {
      setUp: true,
      authenticated: true,
      setupTokenRequired: false,
    });
    client.setQueryData(["/api/applications"], [{ name: "personal" }]);

    markLoggedOut(client);

    expect(client.getQueryData(getGetAuthSessionQueryKey())).toMatchObject({ authenticated: false });
    expect(client.getQueryData(["/api/applications"])).toBeUndefined();
    await vi.waitFor(() => expect(fetched).toBe(1));
  });
});

describe("useThrottle", () => {
  it("waits the given seconds, then says the user may try again", () => {
    vi.useFakeTimers();
    try {
      const { result } = renderHook(() => useThrottle());
      act(() => result.current.start(3));
      expect(result.current.waitSeconds).toBe(3);

      act(() => vi.advanceTimersByTime(2999));
      expect(result.current.waitSeconds).toBe(3);
      act(() => vi.advanceTimersByTime(1));
      expect(result.current.waitSeconds).toBeUndefined();
      expect(result.current.waitEnded).toBe(true);

      act(() => result.current.reset());
      expect(result.current.waitEnded).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});
