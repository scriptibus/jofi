// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from "vitest";
import type { DescriptionSnapshotSummaryResponse, DiffSegmentDto } from "../../api/generated/jofi";
import {
  CHANGED_LINES_SHOWN,
  CONTEXT_LINES,
  changeCounts,
  type DiffRow,
  defaultComparison,
  diffRows,
  linesOf,
} from "./descriptionDiff";

const lines = (prefix: string, count: number) =>
  Array.from({ length: count }, (_, index) => `${prefix} ${index + 1}\n`).join("");

const shown = (rows: DiffRow[]) =>
  rows.map((row) => (row.kind === "line" ? row.text : `[${row.count} folded]`));

function version(id: string, frozen = false): DescriptionSnapshotSummaryResponse {
  return {
    id,
    sourceId: "source",
    contentHash: id,
    reason: "MANUAL",
    capturedAt: "2026-09-30T10:00:00Z",
    frozenAt: frozen ? "2026-09-30T11:00:00Z" : null,
    length: 10,
  };
}

describe("linesOf", () => {
  it("splits at line breaks and keeps empty lines, without a phantom line after the last break", () => {
    expect(linesOf("a\n\nb\n")).toEqual(["a", "", "b"]);
    expect(linesOf("last line without break")).toEqual(["last line without break"]);
  });
});

describe("diffRows", () => {
  it("folds a long unchanged run between changes, keeping context on both sides", () => {
    const segments: DiffSegmentDto[] = [
      { operation: "REMOVED", text: "old\n" },
      { operation: "UNCHANGED", text: lines("same", 20) },
      { operation: "ADDED", text: "new" },
    ];
    const rows = diffRows(segments, new Set());
    expect(shown(rows)).toEqual([
      "old",
      "same 1",
      "same 2",
      "same 3",
      `[${20 - 2 * CONTEXT_LINES} folded]`,
      "same 18",
      "same 19",
      "same 20",
      "new",
    ]);
    expect(diffRows(segments, new Set([1]))).toHaveLength(22);
  });

  it("keeps context only towards the change at the start and end of the text", () => {
    const segments: DiffSegmentDto[] = [
      { operation: "UNCHANGED", text: lines("head", 10) },
      { operation: "ADDED", text: "added\n" },
      { operation: "UNCHANGED", text: lines("tail", 10) },
    ];
    expect(shown(diffRows(segments, new Set()))).toEqual([
      "[7 folded]",
      "head 8",
      "head 9",
      "head 10",
      "added",
      "tail 1",
      "tail 2",
      "tail 3",
      "[7 folded]",
    ]);
  });

  it("does not fold a single line: the button would hide nothing", () => {
    const segments: DiffSegmentDto[] = [
      { operation: "ADDED", text: "a\n" },
      { operation: "UNCHANGED", text: lines("same", 2 * CONTEXT_LINES + 1) },
      { operation: "REMOVED", text: "b" },
    ];
    expect(diffRows(segments, new Set()).every((row) => row.kind === "line")).toBe(true);
  });

  it("shows only the first lines of a very long change until unfolded", () => {
    const segments: DiffSegmentDto[] = [
      { operation: "ADDED", text: lines("line", CHANGED_LINES_SHOWN + 50) },
    ];
    const rows = diffRows(segments, new Set());
    expect(rows).toHaveLength(CHANGED_LINES_SHOWN + 1);
    expect(rows.at(-1)).toMatchObject({ kind: "folded", operation: "ADDED", count: 50, run: 0 });
    expect(diffRows(segments, new Set([0]))).toHaveLength(CHANGED_LINES_SHOWN + 50);
  });

  it("stays small for a 100,000-character rewrite", () => {
    const long = lines("x", 20_000);
    const rows = diffRows(
      [
        { operation: "REMOVED", text: long },
        { operation: "ADDED", text: long },
      ],
      new Set(),
    );
    expect(rows.length).toBe(2 * (CHANGED_LINES_SHOWN + 1));
  });
});

describe("changeCounts", () => {
  it("counts added and removed lines", () => {
    expect(
      changeCounts([
        { operation: "UNCHANGED", text: "a\nb\n" },
        { operation: "REMOVED", text: "c\n" },
        { operation: "ADDED", text: "d\ne\nf" },
      ]),
    ).toEqual({ added: 3, removed: 1 });
  });
});

describe("defaultComparison", () => {
  it("compares the frozen version with the latest", () => {
    expect(defaultComparison([version("a"), version("b", true), version("c"), version("d")])).toEqual({
      from: "b",
      to: "d",
    });
  });

  it("compares the previous with the latest when nothing is frozen or the latest is the frozen one", () => {
    expect(defaultComparison([version("a"), version("b"), version("c")])).toEqual({ from: "b", to: "c" });
    expect(defaultComparison([version("a"), version("b", true)])).toEqual({ from: "a", to: "b" });
  });

  it("compares with the newest version of another source when the source has only one", () => {
    const older = { ...version("x"), sourceId: "other", capturedAt: "2026-09-01T08:00:00Z" };
    const newer = { ...version("y"), sourceId: "other", capturedAt: "2026-09-02T08:00:00Z" };
    expect(defaultComparison([version("a")], [newer, older])).toEqual({ from: "y", to: "a" });
    expect(defaultComparison([], [older, newer])).toBeUndefined();
  });

  it("has nothing to compare with fewer than two versions", () => {
    expect(defaultComparison([version("a")])).toBeUndefined();
    expect(defaultComparison([])).toBeUndefined();
  });
});
