// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ReactNode } from "react";
import type { CostSummaryResponse, CostTotalsResponse, MonthlyCostResponse } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Table, TableCell } from "../../ui";
import { formatCount } from "../backup/files";
import { costFigure, formatMonth } from "./costs";
import { formatUsd } from "./money";
import { PROVIDER_KIND_LABELS, TASK_LABELS } from "./tasks";

export function unpricedText(calls: number): string {
  return m.ai_costs_unpriced({ count: calls, shown: formatCount(calls) });
}

/**
 * A cost: the sum of the priced calls, and below it how many calls had no price. A line whose calls
 * all lack a price says "No price" instead of $0.00, since nothing says it was free.
 */
export function CostCell({ totals }: { totals: CostTotalsResponse }) {
  const { knownMicros, unpricedCalls } = costFigure(totals);
  return (
    <TableCell>
      <span className="font-data">
        {knownMicros === null ? m.ai_costs_unknown() : formatUsd(knownMicros)}
      </span>
      {unpricedCalls > 0 ? <span className="block text-muted">{unpricedText(unpricedCalls)}</span> : null}
    </TableCell>
  );
}

function UsageCells({ totals }: { totals: CostTotalsResponse }) {
  return (
    <>
      <TableCell className="font-data">{formatCount(totals.calls)}</TableCell>
      <TableCell className="font-data">{formatCount(totals.inputTokens)}</TableCell>
      <TableCell className="font-data">{formatCount(totals.outputTokens)}</TableCell>
      <CostCell totals={totals} />
    </>
  );
}

/** A function, not a constant: the labels follow the language the user switches to. */
const usageColumns = () =>
  [
    { id: "calls", label: m.ai_costs_col_calls() },
    { id: "input", label: m.ai_costs_col_input() },
    { id: "output", label: m.ai_costs_col_output() },
    { id: "cost", label: m.ai_costs_col_cost() },
  ] as const;

function Breakdown({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-h3">{title}</h3>
      {children}
    </div>
  );
}

/** The month's costs per task, per provider kind and per model, each as a table. */
export function CostBreakdowns({ summary }: { summary: CostSummaryResponse }) {
  return (
    <div className="flex flex-col gap-6">
      <Breakdown title={m.ai_costs_by_task()}>
        <Table
          label={m.ai_costs_by_task()}
          columns={[{ id: "task", label: m.ai_costs_col_task() }, ...usageColumns()]}
        >
          {summary.byTask.map((line) => (
            <tr key={line.task}>
              <TableCell>{TASK_LABELS[line.task]()}</TableCell>
              <UsageCells totals={line.totals} />
            </tr>
          ))}
        </Table>
      </Breakdown>
      <Breakdown title={m.ai_costs_by_provider()}>
        <Table
          label={m.ai_costs_by_provider()}
          columns={[{ id: "provider", label: m.ai_costs_col_provider() }, ...usageColumns()]}
        >
          {summary.byProviderKind.map((line) => (
            <tr key={line.providerKind}>
              <TableCell>{PROVIDER_KIND_LABELS[line.providerKind]()}</TableCell>
              <UsageCells totals={line.totals} />
            </tr>
          ))}
        </Table>
      </Breakdown>
      <Breakdown title={m.ai_costs_by_model()}>
        <Table
          label={m.ai_costs_by_model()}
          columns={[
            { id: "model", label: m.ai_costs_col_model() },
            { id: "provider", label: m.ai_costs_col_provider() },
            ...usageColumns(),
          ]}
        >
          {summary.byModel.map((line) => (
            <tr key={`${line.providerKind}/${line.model}`}>
              <TableCell className="font-data">{line.model}</TableCell>
              <TableCell>{PROVIDER_KIND_LABELS[line.providerKind]()}</TableCell>
              <UsageCells totals={line.totals} />
            </tr>
          ))}
        </Table>
      </Breakdown>
    </div>
  );
}

/** The monthly history as a table (the text alternative of any chart), newest month first. */
export function CostHistoryTable({ history }: { history: readonly MonthlyCostResponse[] }) {
  return (
    <Table
      label={m.ai_costs_history_label()}
      columns={[{ id: "month", label: m.ai_costs_col_month() }, ...usageColumns()]}
    >
      {[...history].reverse().map((entry) => (
        <tr key={entry.month}>
          <TableCell>{formatMonth(entry.month)}</TableCell>
          <UsageCells totals={entry.totals} />
        </tr>
      ))}
    </Table>
  );
}
