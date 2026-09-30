// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { setupServer } from "msw/node";
import type { ReactNode } from "react";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { ApiProblemError, apiFetch, parseRetryAfter } from "./fetcher";
import { getGetSystemInfoUrl, type SystemInfoResponse, useGetSystemInfo } from "./generated/jofi";
import { GetSystemInfoResponse } from "./generated/jofi.zod";

const systemInfoUrl = new URL(getGetSystemInfoUrl(), window.location.origin).href;
const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe("generated API client", () => {
  it("useGetSystemInfo returns typed data from the backend", async () => {
    server.use(http.get(systemInfoUrl, () => HttpResponse.json({ name: "Jofi", version: "1.2.3" })));

    const { result } = renderHook(() => useGetSystemInfo(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const info: SystemInfoResponse | undefined = result.current.data;
    expect(info).toEqual({ name: "Jofi", version: "1.2.3" });
    expect(GetSystemInfoResponse.parse(info)).toEqual(info);
  });

  it("surfaces RFC 9457 problem details as ApiProblemError", async () => {
    const problem = { type: "about:blank", title: "Internal Server Error", status: 500, detail: "Boom" };
    server.use(
      http.get(systemInfoUrl, () =>
        HttpResponse.json(problem, { status: 500, headers: { "Content-Type": "application/problem+json" } }),
      ),
    );

    const { result } = renderHook(() => useGetSystemInfo(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const error = result.current.error;
    expect(error).toBeInstanceOf(ApiProblemError);
    expect(error?.status).toBe(500);
    expect(error?.problem).toEqual(problem);
    expect(error?.message).toBe("Boom");
  });

  it("keeps the status when the error body is not JSON", async () => {
    server.use(http.get(systemInfoUrl, () => new HttpResponse("Bad gateway", { status: 502 })));

    const { result } = renderHook(() => useGetSystemInfo(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.problem).toEqual({ status: 502 });
    expect(result.current.error?.message).toBe("HTTP 502");
  });

  it("echoes the CSRF cookie in a header on unsafe requests only", async () => {
    const seen: Record<string, string | null> = {};
    const url = new URL("/api/test/csrf", window.location.origin).href;
    server.use(
      http.all(url, ({ request }) => {
        seen[request.method] = request.headers.get("X-XSRF-TOKEN");
        return new HttpResponse(null, { status: 204 });
      }),
    );
    document.cookie = "XSRF-TOKEN=abc%2F123; path=/";

    await apiFetch("/api/test/csrf", { method: "POST" });
    await apiFetch("/api/test/csrf", { method: "put", headers: { "Content-Type": "application/json" } });
    await apiFetch("/api/test/csrf");

    expect(seen).toEqual({ POST: "abc/123", PUT: "abc/123", GET: null });
    document.cookie = "XSRF-TOKEN=; max-age=0; path=/";
  });

  it("sends no CSRF header while there is no cookie", async () => {
    let header: string | null = "unset";
    const url = new URL("/api/test/csrf", window.location.origin).href;
    server.use(
      http.post(url, ({ request }) => {
        header = request.headers.get("X-XSRF-TOKEN");
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await apiFetch("/api/test/csrf", { method: "POST" });

    expect(header).toBeNull();
  });

  it("carries Retry-After of a throttled answer", async () => {
    server.use(
      http.get(systemInfoUrl, () =>
        HttpResponse.json(
          { status: 429, type: "urn:jofi:problem:system:login-throttled" },
          { status: 429, headers: { "Content-Type": "application/problem+json", "Retry-After": "8" } },
        ),
      ),
    );

    const { result } = renderHook(() => useGetSystemInfo(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.retryAfterSeconds).toBe(8);
  });

  it("parses Retry-After as seconds or an HTTP date", () => {
    const now = Date.parse("2026-09-30T10:00:00Z");
    expect(parseRetryAfter("120", now)).toBe(120);
    expect(parseRetryAfter("Wed, 30 Sep 2026 10:00:30 GMT", now)).toBe(30);
    expect(parseRetryAfter("Wed, 30 Sep 2026 09:00:00 GMT", now)).toBe(0);
    expect(parseRetryAfter("soon", now)).toBeUndefined();
    expect(parseRetryAfter(null, now)).toBeUndefined();
  });

  it("the Zod schema rejects a response that breaks the contract", () => {
    expect(GetSystemInfoResponse.safeParse({ name: "Jofi" }).success).toBe(false);
  });
});
