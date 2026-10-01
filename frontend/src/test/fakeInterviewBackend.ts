// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for an application's interview endpoints (#91, ADR-0048) as MSW handlers, next to
// `fakeApplicationBackend`. It resolves the agreed wall-clock time in its zone to the instant like the server
// (400 `timeZone` INVALID_TIME_ZONE for a zone it does not know, `localStart` OUT_OF_RANGE outside 2000–2099),
// answers 409 `version-conflict` for a stale `basedOnVersion`, 404 `interview-not-found`, and a 428 whose effect
// names `<TYPE> <localStart> <zone>` before a delete. With a timeline state it adds and removes the interview's
// timeline entry, as the server's timeline would show it.

import { HttpResponse, http } from "msw";
import type { InterviewRequest, InterviewResponse, UpdateInterviewRequest } from "../api/generated/jofi";
import type { FakeTimelineState } from "./fakeTimelineBackend";

const origin = () => window.location.origin;
const json = (body: Record<string, unknown>, status: number) =>
  HttpResponse.json(body, { status, headers: { "Content-Type": "application/problem+json" } });
const problem = (status: number, code: string) =>
  json({ type: `urn:jofi:problem:applications:${code}`, title: "Problem", status, detail: code }, status);
const refused = (field: string, code: string) =>
  json(
    {
      type: "urn:jofi:problem:applications:invalid-application",
      status: 400,
      violations: [{ field, problem: code }],
    },
    400,
  );

export const INTERVIEW_DELETE_TOKEN = "interview-delete-t0k3n";

/** Milliseconds `zone`'s clocks are ahead of UTC at `instant`; throws a RangeError for an unknown zone. */
function offsetAt(zone: string, instant: number): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: zone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(new Date(instant));
  const part = (type: string) => Number(parts.find((candidate) => candidate.type === type)?.value);
  const onClock = Date.UTC(
    part("year"),
    part("month") - 1,
    part("day"),
    part("hour"),
    part("minute"),
    part("second"),
  );
  return onClock - instant;
}

/** The instant a wall-clock time in `zone` is (good enough for tests: no gap or overlap handling). */
export function instantOf(localStart: string, zone: string): string {
  const asUtc = Date.parse(`${localStart.length === 16 ? `${localStart}:00` : localStart}Z`);
  const guess = asUtc - offsetAt(zone, asUtc);
  return new Date(asUtc - offsetAt(zone, guess)).toISOString().replace(".000Z", "Z");
}

export function anInterview(
  applicationId: string,
  overrides: Partial<InterviewResponse> = {},
): InterviewResponse {
  const localStart = overrides.localStart ?? "2026-10-05T10:00";
  const timeZone = overrides.timeZone ?? "Europe/Berlin";
  return {
    id: crypto.randomUUID(),
    applicationId,
    type: "PHONE_SCREEN",
    localStart,
    timeZone,
    startsAt: instantOf(localStart, timeZone),
    participantIds: [],
    preparationNotes: null,
    notes: null,
    outcome: null,
    version: 0,
    createdAt: "2026-09-30T10:00:00Z",
    updatedAt: "2026-09-30T10:00:00Z",
    ...overrides,
  };
}

export interface FakeInterviewState {
  interviews: InterviewResponse[];
  /** Every accepted `POST` body, in order. */
  logs: InterviewRequest[];
  /** Every accepted `PUT` body, in order. */
  updates: UpdateInterviewRequest[];
  /** Delete calls seen: `first` without token, `confirmed` with it. */
  deleteCalls: ("first" | "confirmed")[];
  /** The contacts that exist; when set, any other participant is refused (`participantIds` NOT_FOUND). */
  knownContactIds?: () => string[];
  /** The list fails (to show its own failure; a 4xx, so the query does not retry). */
  listFails?: boolean;
  /** The timeline whose entries follow the interviews. */
  timeline?: FakeTimelineState;
}

function validate(details: InterviewRequest, state: FakeInterviewState) {
  try {
    offsetAt(details.timeZone, 0);
  } catch {
    return refused("timeZone", "INVALID_TIME_ZONE");
  }
  const year = Number(details.localStart.slice(0, 4));
  if (year < 2000 || year > 2099) return refused("localStart", "OUT_OF_RANGE");
  const known = state.knownContactIds?.();
  if (known && details.participantIds.some((id) => !known.includes(id)))
    return refused("participantIds", "NOT_FOUND");
  return undefined;
}

function withDetails(interview: InterviewResponse, details: InterviewRequest): InterviewResponse {
  return {
    ...interview,
    type: details.type,
    localStart: details.localStart,
    timeZone: details.timeZone,
    startsAt: instantOf(details.localStart, details.timeZone),
    participantIds: [...new Set(details.participantIds)],
    preparationNotes: details.preparationNotes ?? null,
    notes: details.notes ?? null,
    outcome: details.outcome ?? null,
  };
}

export function fakeInterviewBackend(initial: Partial<FakeInterviewState> = {}) {
  const state: FakeInterviewState = { interviews: [], logs: [], updates: [], deleteCalls: [], ...initial };
  const path = `${origin()}/api/applications/:id/interviews`;
  const find = (applicationId: unknown, id: unknown) =>
    state.interviews.find((interview) => interview.applicationId === applicationId && interview.id === id);
  const toTimeline = (interview: InterviewResponse) => {
    if (!state.timeline) return;
    state.timeline.entries = [
      ...state.timeline.entries.filter((entry) => entry.id !== interview.id),
      {
        id: interview.id,
        kind: "INTERVIEW",
        occurredAt: interview.startsAt,
        interview: { type: interview.type, localStart: interview.localStart, timeZone: interview.timeZone },
      },
    ];
  };

  const handlers = [
    http.get(path, ({ params }) => {
      if (state.listFails) return problem(404, "application-not-found");
      const interviews = state.interviews
        .filter((interview) => interview.applicationId === params.id)
        .sort((a, b) => a.startsAt.localeCompare(b.startsAt));
      return HttpResponse.json({ interviews });
    }),
    http.post(path, async ({ request, params }) => {
      const body = (await request.json()) as InterviewRequest;
      const invalid = validate(body, state);
      if (invalid) return invalid;
      state.logs.push(body);
      const created = withDetails(anInterview(String(params.id)), body);
      state.interviews = [...state.interviews, created];
      toTimeline(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.put(`${path}/:interviewId`, async ({ request, params }) => {
      const interview = find(params.id, params.interviewId);
      if (!interview) return problem(404, "interview-not-found");
      const body = (await request.json()) as UpdateInterviewRequest;
      if (body.basedOnVersion !== interview.version) return problem(409, "version-conflict");
      const invalid = validate(body.details, state);
      if (invalid) return invalid;
      state.updates.push(body);
      const saved = { ...withDetails(interview, body.details), version: interview.version + 1 };
      state.interviews = state.interviews.map((other) => (other.id === saved.id ? saved : other));
      toTimeline(saved);
      return HttpResponse.json(saved);
    }),
    http.delete(`${path}/:interviewId`, ({ request, params }) => {
      const interview = find(params.id, params.interviewId);
      if (!interview) return problem(404, "interview-not-found");
      if (request.headers.get("Jofi-Confirmation") !== INTERVIEW_DELETE_TOKEN) {
        state.deleteCalls.push("first");
        return json(
          {
            type: "urn:jofi:problem:shared:confirmation-required",
            status: 428,
            confirmationToken: INTERVIEW_DELETE_TOKEN,
            expiresAt: "2026-09-30T12:05:00Z",
            operation: "interviews.delete",
            targets: [interview.id],
            effect: {
              kind: "interview",
              name: `${interview.type} ${interview.localStart} ${interview.timeZone}`,
              counts: {},
            },
          },
          428,
        );
      }
      state.deleteCalls.push("confirmed");
      state.interviews = state.interviews.filter((other) => other.id !== interview.id);
      if (state.timeline)
        state.timeline.entries = state.timeline.entries.filter((entry) => entry.id !== interview.id);
      return new HttpResponse(null, { status: 204 });
    }),
  ];
  return { state, handlers };
}
