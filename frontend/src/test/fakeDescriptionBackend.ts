// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// An in-memory stand-in for the description snapshot endpoints (#86) as MSW handlers, next to
// `fakeApplicationBackend`. Recording the latest text again answers `added: false`; an empty text is a 400
// `REQUIRED` violation. The diff keeps the common first and last lines and turns the middle into one removed
// and one added segment, which is all these tests need of the backend's line diff.

import { HttpResponse, http } from "msw";
import type {
  DescriptionSnapshotResponse,
  DiffSegmentDto,
  RecordDescriptionSnapshotRequest,
} from "../api/generated/jofi";

const origin = () => window.location.origin;
const problem = (status: number, code: string) =>
  HttpResponse.json(
    { type: `urn:jofi:problem:applications:${code}`, title: "Problem", status, detail: code },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );

export function aSnapshot(
  sourceId: string,
  description: string,
  overrides: Partial<DescriptionSnapshotResponse> = {},
): DescriptionSnapshotResponse {
  return {
    id: crypto.randomUUID(),
    sourceId,
    description,
    contentHash: description,
    reason: "MANUAL",
    capturedAt: "2026-09-30T10:00:00Z",
    frozenAt: null,
    ...overrides,
  };
}

const summary = ({ description, ...rest }: DescriptionSnapshotResponse) => ({
  ...rest,
  length: [...description].length,
});

const linesOf = (text: string) => text.match(/[^\n]*\n|[^\n]+$/g) ?? [];

/** Common first and last lines unchanged, the rest removed and added. */
function naiveDiff(from: string, to: string): DiffSegmentDto[] {
  const old = linesOf(from);
  const next = linesOf(to);
  let prefix = 0;
  while (prefix < old.length && prefix < next.length && old[prefix] === next[prefix]) prefix++;
  let suffix = 0;
  while (
    suffix < Math.min(old.length, next.length) - prefix &&
    old[old.length - 1 - suffix] === next[next.length - 1 - suffix]
  )
    suffix++;
  const segments: DiffSegmentDto[] = [
    { operation: "UNCHANGED", text: old.slice(0, prefix).join("") },
    { operation: "REMOVED", text: old.slice(prefix, old.length - suffix).join("") },
    { operation: "ADDED", text: next.slice(prefix, next.length - suffix).join("") },
    { operation: "UNCHANGED", text: old.slice(old.length - suffix).join("") },
  ];
  return segments.filter((segment) => segment.text !== "");
}

export interface FakeDescriptionState {
  /** Every snapshot of every source, oldest first. */
  snapshots: DescriptionSnapshotResponse[];
  /** Every recorded text, in order (also unchanged ones). */
  recorded: string[];
  /** Every diff asked for, as `from→to`. */
  diffs: string[];
}

export function fakeDescriptionBackend(initial: Partial<FakeDescriptionState> = {}) {
  const state: FakeDescriptionState = { snapshots: [], recorded: [], diffs: [], ...initial };
  const find = (id: unknown) => state.snapshots.find((snapshot) => snapshot.id === id);
  const handlers = [
    http.get(`${origin()}/api/applications/:id/sources/:sourceId/snapshots`, ({ params }) =>
      HttpResponse.json({
        snapshots: state.snapshots.filter((snapshot) => snapshot.sourceId === params.sourceId).map(summary),
      }),
    ),
    http.post(`${origin()}/api/applications/:id/sources/:sourceId/snapshots`, async ({ request, params }) => {
      const { description } = (await request.json()) as RecordDescriptionSnapshotRequest;
      const text = description.trim();
      if (text === "")
        return HttpResponse.json(
          { status: 400, violations: [{ field: "description", problem: "REQUIRED" }] },
          { status: 400, headers: { "Content-Type": "application/problem+json" } },
        );
      state.recorded.push(text);
      const latest = state.snapshots.filter((snapshot) => snapshot.sourceId === params.sourceId).at(-1);
      if (latest?.description === text) return HttpResponse.json({ added: false, snapshot: summary(latest) });
      const created = aSnapshot(String(params.sourceId), text, { capturedAt: "2026-10-01T09:00:00Z" });
      state.snapshots = [...state.snapshots, created];
      return HttpResponse.json({ added: true, snapshot: summary(created) });
    }),
    http.get(`${origin()}/api/applications/:id/snapshots/:snapshotId`, ({ params }) => {
      const snapshot = find(params.snapshotId);
      return snapshot ? HttpResponse.json(snapshot) : problem(404, "snapshot-not-found");
    }),
    http.get(`${origin()}/api/applications/:id/description-diff`, ({ request }) => {
      const url = new URL(request.url);
      const from = find(url.searchParams.get("from"));
      const to = find(url.searchParams.get("to"));
      if (!from || !to) return problem(404, "snapshot-not-found");
      state.diffs.push(`${from.id}→${to.id}`);
      return HttpResponse.json({
        from: from.id,
        to: to.id,
        segments: naiveDiff(from.description, to.description),
      });
    }),
  ];
  return { state, handlers };
}
