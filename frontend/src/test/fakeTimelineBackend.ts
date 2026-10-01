// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the application timeline endpoint (#87) as MSW handlers, next to
// `fakeApplicationBackend`. It answers the entries newest first, `pageSize` at a time, with an opaque cursor
// (here: the offset) for the next page; `failing` answers 404 `not-found` (a page or the first one).

import { HttpResponse, http } from "msw";
import type { ChangeActorDto, TimelineEntryResponse } from "../api/generated/jofi";

const origin = () => window.location.origin;

export interface FakeTimelineState {
  entries: TimelineEntryResponse[];
  pageSize: number;
  /** Answers 404 for the first page (`"first"`) or for every later one (`"more"`). */
  failing?: "first" | "more" | undefined;
}

export const user: ChangeActorDto = { kind: "USER", name: null };

/** An entry of `kind` at `occurredAt` with its detail object; the id is unique. */
export function anEntry(
  occurredAt: string,
  details: Omit<TimelineEntryResponse, "id" | "kind" | "occurredAt"> & {
    kind: TimelineEntryResponse["kind"];
  },
): TimelineEntryResponse {
  return { id: crypto.randomUUID(), occurredAt, ...details };
}

export function fakeTimelineBackend(initial: Partial<FakeTimelineState> = {}) {
  const state: FakeTimelineState = { entries: [], pageSize: 50, ...initial };
  const handlers = [
    http.get(`${origin()}/api/applications/:id/timeline`, ({ request }) => {
      const cursor = new URL(request.url).searchParams.get("cursor");
      const failing = cursor === null ? state.failing === "first" : state.failing === "more";
      if (failing)
        return HttpResponse.json(
          { type: "urn:jofi:problem:applications:not-found", title: "Not found", status: 404 },
          { status: 404, headers: { "Content-Type": "application/problem+json" } },
        );
      const newestFirst = [...state.entries].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt));
      const offset = cursor === null ? 0 : Number(cursor);
      const end = offset + state.pageSize;
      return HttpResponse.json({
        entries: newestFirst.slice(offset, end),
        nextCursor: end < newestFirst.length ? String(end) : null,
      });
    }),
  ];
  return { state, handlers };
}
