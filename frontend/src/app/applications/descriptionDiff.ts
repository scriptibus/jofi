// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { DescriptionSnapshotSummaryResponse, DiffSegmentDto } from "../../api/generated/jofi";

export type DiffOperation = DiffSegmentDto["operation"];

/** Unchanged lines kept around a change, so it can be read in context; the rest of the run folds away. */
export const CONTEXT_LINES = 3;
/** Changed lines shown of one run before the rest folds away: a rewritten 100,000-character posting stays quick. */
export const CHANGED_LINES_SHOWN = 200;

/** One row of the unified diff: a line, or a folded run of lines that a button unfolds. */
export type DiffRow =
  | { kind: "line"; key: string; operation: DiffOperation; text: string }
  | { kind: "folded"; key: string; operation: DiffOperation; count: number; run: number };

/** The lines of a segment. Every line but the text's last ends in `\n` (backend `LineDiff`). */
export function linesOf(text: string): string[] {
  const lines = text.split("\n");
  if (lines.at(-1) === "") lines.pop();
  return lines;
}

const lineRows = (run: number, operation: DiffOperation, lines: string[], offset: number): DiffRow[] =>
  lines.map((text, index) => ({ kind: "line", key: `${run}:${offset + index}`, operation, text }));

/**
 * The segments as rows. Unchanged runs keep [CONTEXT_LINES] lines next to each change and fold the rest;
 * changed runs show their first [CHANGED_LINES_SHOWN] lines. A run in [unfolded] (by index) shows in full.
 */
export function diffRows(segments: readonly DiffSegmentDto[], unfolded: ReadonlySet<number>): DiffRow[] {
  return segments.flatMap((segment, run) => {
    const lines = linesOf(segment.text);
    const { operation } = segment;
    if (unfolded.has(run)) return lineRows(run, operation, lines, 0);
    if (operation !== "UNCHANGED") {
      if (lines.length <= CHANGED_LINES_SHOWN) return lineRows(run, operation, lines, 0);
      const count = lines.length - CHANGED_LINES_SHOWN;
      return [
        ...lineRows(run, operation, lines.slice(0, CHANGED_LINES_SHOWN), 0),
        { kind: "folded", key: `${run}:folded`, operation, count, run },
      ];
    }
    const before = run === 0 ? 0 : CONTEXT_LINES;
    const after = run === segments.length - 1 ? 0 : CONTEXT_LINES;
    const count = lines.length - before - after;
    // Folding a single line would hide nothing worth a button.
    if (count < 2) return lineRows(run, operation, lines, 0);
    return [
      ...lineRows(run, operation, lines.slice(0, before), 0),
      { kind: "folded", key: `${run}:folded`, operation, count, run },
      ...lineRows(run, operation, lines.slice(lines.length - after), lines.length - after),
    ];
  });
}

/** How many lines the diff adds and removes. */
export function changeCounts(segments: readonly DiffSegmentDto[]): { added: number; removed: number } {
  let added = 0;
  let removed = 0;
  for (const segment of segments) {
    if (segment.operation === "ADDED") added += linesOf(segment.text).length;
    if (segment.operation === "REMOVED") removed += linesOf(segment.text).length;
  }
  return { added, removed };
}

/**
 * Which versions to compare first: what was applied for (the frozen one) against the latest, else the
 * previous against the latest. [versions] are the chosen source's, oldest first. With only one, the newest
 * version of another source ([others]) comes first, the same job posted elsewhere; undefined without a pair.
 */
export function defaultComparison(
  versions: readonly DescriptionSnapshotSummaryResponse[],
  others: readonly DescriptionSnapshotSummaryResponse[] = [],
): { from: string; to: string } | undefined {
  const latest = versions.at(-1);
  if (latest === undefined) return undefined;
  const frozen = versions.find((version) => version.frozenAt != null);
  const newestOther = others.reduce<DescriptionSnapshotSummaryResponse | undefined>(
    (newest, version) => (newest === undefined || version.capturedAt > newest.capturedAt ? version : newest),
    undefined,
  );
  const from = frozen && frozen.id !== latest.id ? frozen : (versions.at(-2) ?? newestOther);
  return from ? { from: from.id, to: latest.id } : undefined;
}
