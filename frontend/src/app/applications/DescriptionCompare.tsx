// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useState } from "react";
import { type DiffSegmentDto, useDiffDescriptionSnapshots } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { getLocale } from "../../paraglide/runtime.js";
import { Button, Select, type SelectGroup } from "../../ui";
import { LoadFailure } from "./DescriptionVersions";
import { changeCounts, type DiffOperation, type DiffRow, diffRows } from "./descriptionDiff";

/** Two versions to compare, by snapshot id; they may belong to different sources (the same job posted twice). */
export interface Comparison {
  from: string;
  to: string;
}

/**
 * Picks two versions of any of the application's sources and shows the line diff between them as one
 * column (unified): removed lines, then added ones, each marked with a sign and words, not colour alone.
 */
export function CompareVersions({
  applicationId,
  groups,
  names,
  comparison,
  onChange,
}: {
  applicationId: string;
  /** Every version, grouped by source. */
  groups: readonly SelectGroup[];
  /** Each version's full name ("Version 2 (Found by a scanner)") by id, for the diff's label. */
  names: ReadonlyMap<string, string>;
  comparison: Comparison;
  onChange: (comparison: Comparison) => void;
}) {
  const { from, to } = comparison;
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <Select
          label={m.application_description_compare_before()}
          placeholder={m.application_description_compare_choose()}
          groups={groups}
          value={from}
          onChange={(id) => onChange({ from: id, to })}
        />
        <Select
          label={m.application_description_compare_after()}
          placeholder={m.application_description_compare_choose()}
          groups={groups}
          value={to}
          onChange={(id) => onChange({ from, to: id })}
        />
      </div>
      {from === to ? (
        <p className="text-muted">{m.application_description_compare_same()}</p>
      ) : (
        <Diff
          key={`${from}:${to}`}
          applicationId={applicationId}
          comparison={comparison}
          label={m.application_description_diff_label({
            before: names.get(from) ?? "",
            after: names.get(to) ?? "",
          })}
        />
      )}
    </>
  );
}

function Diff({
  applicationId,
  comparison,
  label,
}: {
  applicationId: string;
  comparison: Comparison;
  label: string;
}) {
  const diff = useDiffDescriptionSnapshots(applicationId, comparison, {
    query: { meta: { errorHandledLocally: true }, staleTime: Number.POSITIVE_INFINITY },
  });
  if (diff.isError)
    return (
      <LoadFailure
        error={diff.error}
        message={m.application_description_diff_failed()}
        onRetry={() => void diff.refetch()}
      />
    );
  if (diff.data === undefined) return <p role="status">{m.loading()}</p>;
  return <DiffView segments={diff.data.segments} label={label} />;
}

const MARKERS: Record<DiffOperation, string> = { ADDED: "+", REMOVED: "−", UNCHANGED: " " };
const LINE_STYLES: Record<DiffOperation, string> = {
  ADDED: "border-good bg-good/10",
  REMOVED: "border-bad bg-bad/10",
  UNCHANGED: "border-transparent",
};

/** The diff's lines; long unchanged stretches and very long changes fold behind a button. */
export function DiffView({ segments, label }: { segments: readonly DiffSegmentDto[]; label: string }) {
  const [unfolded, setUnfolded] = useState<ReadonlySet<number>>(new Set());
  const { added, removed } = changeCounts(segments);
  if (added === 0 && removed === 0)
    return <p className="text-muted">{m.application_description_diff_identical()}</p>;
  const unfold = (run: number) => setUnfolded((before) => new Set(before).add(run));
  return (
    <div className="flex flex-col gap-2">
      <p className="text-muted">{m.application_description_diff_counts({ added, removed })}</p>
      <ol aria-label={label} className="flex flex-col rounded border border-line py-1 font-data text-body">
        {diffRows(segments, unfolded).map((row) =>
          row.kind === "line" ? (
            <DiffLine key={row.key} row={row} />
          ) : (
            <Fold key={row.key} row={row} onUnfold={unfold} />
          ),
        )}
      </ol>
    </div>
  );
}

function DiffLine({ row }: { row: Extract<DiffRow, { kind: "line" }> }) {
  const text = <span className="min-w-0 whitespace-pre-wrap break-words">{row.text}</span>;
  let content = text;
  if (row.operation === "ADDED")
    content = (
      <ins className="min-w-0 no-underline">
        <span className="sr-only">{m.application_description_diff_added()} </span>
        {text}
      </ins>
    );
  if (row.operation === "REMOVED")
    content = (
      <del className="min-w-0 no-underline">
        <span className="sr-only">{m.application_description_diff_removed()} </span>
        {text}
      </del>
    );
  return (
    <li className={`flex min-h-6 gap-2 border-l-4 px-2 ${LINE_STYLES[row.operation]}`}>
      <span aria-hidden="true" className="w-3 shrink-0 select-none font-semibold">
        {MARKERS[row.operation]}
      </span>
      {content}
    </li>
  );
}

function Fold({
  row,
  onUnfold,
}: {
  row: Extract<DiffRow, { kind: "folded" }>;
  onUnfold: (run: number) => void;
}) {
  const words = { count: row.count, shown: new Intl.NumberFormat(getLocale()).format(row.count) };
  return (
    <li className="px-2 py-1">
      <Button variant="secondary" className="w-full font-body" onPress={() => onUnfold(row.run)}>
        {row.operation === "UNCHANGED"
          ? m.application_description_show_unchanged(words)
          : m.application_description_show_more(words)}
      </Button>
    </li>
  );
}
