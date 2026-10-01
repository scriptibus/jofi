// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { setupServer } from "msw/node";
import type { ReactNode } from "react";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { aCountdown, fakeCountdownBackend } from "../../test/fakeCountdownBackend";
import { useAddCountdown, useDashboardCountdowns, useToday } from "./useCountdowns";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

// Only the clock and the interval are fake: MSW and React Query keep their real timeouts and promises.
// `vi.waitFor` (not Testing Library's, whose polling interval would be fake) moves the fake time on as it waits.
beforeEach(() => vi.useFakeTimers({ toFake: ["Date", "setInterval", "clearInterval"] }));
afterEach(() => vi.useRealTimers());

/** 23:59:30 on 30 September 2026 in Berlin (UTC+2). */
const BEFORE_BERLIN_MIDNIGHT = new Date("2026-09-30T21:59:30Z");

function wrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

describe("useToday", () => {
  it("is today on the viewer's calendar and moves on at the viewer's midnight", () => {
    vi.setSystemTime(BEFORE_BERLIN_MIDNIGHT);
    const { result } = renderHook(() => useToday("Europe/Berlin"));
    expect(result.current).toBe("2026-09-30");

    act(() => vi.advanceTimersByTime(60_000));
    expect(result.current).toBe("2026-10-01");
  });

  it("follows the zone it is given", () => {
    vi.setSystemTime(BEFORE_BERLIN_MIDNIGHT);
    const { result, rerender } = renderHook(({ zone }) => useToday(zone), {
      initialProps: { zone: "Europe/Berlin" },
    });
    expect(result.current).toBe("2026-09-30");
    rerender({ zone: "Asia/Tokyo" });
    expect(result.current).toBe("2026-10-01");
  });

  it("stops looking once unmounted", () => {
    vi.setSystemTime(BEFORE_BERLIN_MIDNIGHT);
    const { unmount } = renderHook(() => useToday("Europe/Berlin"));
    expect(vi.getTimerCount()).toBe(1);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe("useDashboardCountdowns", () => {
  it("asks in the viewer's zone and loads again when the day changes", async () => {
    vi.setSystemTime(BEFORE_BERLIN_MIDNIGHT);
    const backend = fakeCountdownBackend({ countdowns: [aCountdown({ title: "Notice ends" })] });
    server.use(...backend.handlers);
    const { result } = renderHook(() => useDashboardCountdowns("Europe/Berlin"), { wrapper: wrapper() });

    await vi.waitFor(() => expect(result.current.list.data?.countdowns).toHaveLength(1));
    expect(backend.state.listZones).toEqual(["Europe/Berlin"]);
    expect(result.current.today).toBe("2026-09-30");

    // Within the same day nothing is loaded again.
    act(() => vi.advanceTimersByTime(20_000));
    expect(backend.state.listZones).toHaveLength(1);

    act(() => vi.advanceTimersByTime(60_000));
    expect(result.current.today).toBe("2026-10-01");
    await vi.waitFor(() => expect(backend.state.listZones).toHaveLength(2));
  });
});

describe("useAddCountdown", () => {
  it("creates the countdown and reloads the dashboard list", async () => {
    const backend = fakeCountdownBackend();
    server.use(...backend.handlers);
    const render = wrapper();
    const { result } = renderHook(
      () => ({ list: useDashboardCountdowns("UTC").list, add: useAddCountdown() }),
      { wrapper: render },
    );
    await vi.waitFor(() => expect(result.current.list.data?.countdowns).toEqual([]));

    await act(() => result.current.add.mutateAsync({ title: "Notice ends", targetDate: "2026-12-31" }));

    expect(backend.state.creates).toEqual([{ title: "Notice ends", targetDate: "2026-12-31" }]);
    await vi.waitFor(() =>
      expect(result.current.list.data?.countdowns.map((countdown) => countdown.title)).toEqual([
        "Notice ends",
      ]),
    );
  });
});
