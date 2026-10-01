// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the countdown endpoints (#112), as MSW handlers. It mirrors the backend's status codes
// and problem types: 400 violations, 404 `countdown-not-found`, a 428 before a delete, and the dashboard list with
// the custom countdowns plus the derived ones the test gives (`derived`), in the given order. Ordering and the
// calendar run in the backend's tests and in e2e.

import { HttpResponse, http } from "msw";
import type { CountdownRequest, CountdownResponse, DashboardCountdownResponse } from "../api/generated/jofi";

const origin = () => window.location.origin;
const json = (body: Record<string, unknown>, status: number) =>
  HttpResponse.json(body, { status, headers: { "Content-Type": "application/problem+json" } });
const problem = (status: number, code: string) =>
  json({ type: `urn:jofi:problem:tasks:${code}`, title: "Problem", status, detail: code }, status);

export const COUNTDOWN_DELETE_TOKEN = "countdown-delete-t0k3n";

export function aCountdown(overrides: Partial<CountdownResponse> = {}): CountdownResponse {
  return {
    id: crypto.randomUUID(),
    title: "End of notice period",
    targetDate: "2026-12-31",
    version: 0,
    createdAt: "2026-09-30T10:00:00Z",
    updatedAt: "2026-09-30T10:00:00Z",
    ...overrides,
  };
}

/** A derived dashboard countdown (not custom), e.g. an application's deadline. */
export function aDashboardCountdown(
  overrides: Partial<DashboardCountdownResponse> = {},
): DashboardCountdownResponse {
  return {
    source: "APPLICATION_DEADLINE",
    title: "Platform Engineer",
    targetDate: "2026-10-10",
    targetAt: null,
    localTarget: null,
    timeZone: null,
    subjectType: "application",
    subjectId: crypto.randomUUID(),
    applicationId: null,
    ...overrides,
  };
}

export interface FakeCountdownState {
  countdowns: CountdownResponse[];
  /** Next interview, deadlines and offer answers, listed before the custom countdowns. */
  derived: DashboardCountdownResponse[];
  /** The `timeZone` of every dashboard list request, in order. */
  listZones: string[];
  /** Every accepted `POST` body, in order. */
  creates: CountdownRequest[];
  /** Every `POST` that arrived, accepted or not. */
  createCalls: number;
  /** Target dates the server refuses as `OUT_OF_RANGE`, whatever the client thinks of them. */
  refusedDates: string[];
  /** Delete calls seen: `first` without token, `confirmed` with it. */
  deleteCalls: ("first" | "confirmed")[];
  /** While true, the dashboard list answers 503 `storage-unavailable`. */
  unavailable: boolean;
}

function toDashboard(countdown: CountdownResponse): DashboardCountdownResponse {
  return {
    source: "CUSTOM",
    title: countdown.title,
    targetDate: countdown.targetDate,
    targetAt: null,
    localTarget: null,
    timeZone: null,
    subjectType: "countdown",
    subjectId: countdown.id,
    applicationId: null,
  };
}

function violations(details: CountdownRequest, refusedDates: string[]) {
  const found: { field: string; problem: string }[] = [];
  if (details.title.trim() === "") found.push({ field: "title", problem: "REQUIRED" });
  if (refusedDates.includes(details.targetDate)) found.push({ field: "targetDate", problem: "OUT_OF_RANGE" });
  return found;
}

export function fakeCountdownBackend(initial: Partial<FakeCountdownState> = {}) {
  const state: FakeCountdownState = {
    countdowns: [],
    derived: [],
    listZones: [],
    creates: [],
    createCalls: 0,
    refusedDates: [],
    deleteCalls: [],
    unavailable: false,
    ...initial,
  };

  const handlers = [
    http.get(`${origin()}/api/dashboard/countdowns`, ({ request }) => {
      state.listZones.push(new URL(request.url).searchParams.get("timeZone") ?? "");
      if (state.unavailable) return problem(503, "storage-unavailable");
      return HttpResponse.json({ countdowns: [...state.derived, ...state.countdowns.map(toDashboard)] });
    }),
    http.get(`${origin()}/api/countdowns`, () => HttpResponse.json({ countdowns: state.countdowns })),
    http.post(`${origin()}/api/countdowns`, async ({ request }) => {
      const body = (await request.json()) as CountdownRequest;
      state.createCalls += 1;
      const found = violations(body, state.refusedDates);
      if (found.length > 0)
        return json({ type: "urn:jofi:problem:tasks:invalid-task", status: 400, violations: found }, 400);
      state.creates.push(body);
      const created = aCountdown({ title: body.title, targetDate: body.targetDate });
      state.countdowns = [...state.countdowns, created];
      return HttpResponse.json(created, { status: 201 });
    }),
    http.delete(`${origin()}/api/countdowns/:id`, ({ request, params }) => {
      const countdown = state.countdowns.find((other) => other.id === params.id);
      if (!countdown) return problem(404, "countdown-not-found");
      if (request.headers.get("Jofi-Confirmation") !== COUNTDOWN_DELETE_TOKEN) {
        state.deleteCalls.push("first");
        return json(
          {
            type: "urn:jofi:problem:shared:confirmation-required",
            status: 428,
            confirmationToken: COUNTDOWN_DELETE_TOKEN,
            expiresAt: "2026-09-30T12:05:00Z",
            operation: "countdowns.delete",
            targets: [countdown.id],
            effect: { kind: "countdown", name: countdown.title, counts: {} },
          },
          428,
        );
      }
      state.deleteCalls.push("confirmed");
      state.countdowns = state.countdowns.filter((other) => other.id !== countdown.id);
      return new HttpResponse(null, { status: 204 });
    }),
  ];

  return { state, handlers };
}
