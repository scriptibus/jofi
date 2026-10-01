// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useState } from "react";
import { type CostSummaryResponse, useGetCostHistory, useGetCostSummary } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, Form, ProgressBar, Select } from "../../ui";
import { formatCount } from "../backup/files";
import { CostBreakdowns, CostHistoryTable, unpricedText } from "./CostTables";
import { budgetPercent, costFigure, formatMonth, formatPercent } from "./costs";
import { formatUsd } from "./money";
import { describeSetupError, fieldErrorsOf } from "./setupProblems";

/** The history table and the month picker reach this far back (the API allows 24). */
const HISTORY_MONTHS = 12;
const local = { query: { meta: { errorHandledLocally: true } } } as const;

/**
 * Settings > AI > Costs (spec §3.2, §10.1): the month's AI costs against the cap, per task, provider and
 * model, a picker for earlier months and the monthly history. Amounts are USD micros, shown through
 * `formatUsd`; calls without a known price are counted and never turned into $0.
 */
export function CostsCard() {
  const history = useGetCostHistory({ months: HISTORY_MONTHS }, local);
  const months = history.data ?? [];
  // The server's own current month (UTC): the picker never offers a later one, and no browser clock is involved.
  const current = months.at(-1)?.month;
  const [picked, setPicked] = useState<string | null>(null);
  const month = picked !== null && picked !== current ? picked : undefined;
  const summary = useGetCostSummary(month === undefined ? undefined : { month }, local);
  const monthError = summary.isError ? fieldErrorsOf(summary.error).month : undefined;

  return (
    <div className="flex flex-col gap-6">
      <p className="max-w-prose text-muted">{m.ai_costs_intro()}</p>
      {history.isSuccess && current !== undefined ? (
        <Form validationErrors={monthError ? { month: monthError } : {}} className="max-w-xs">
          <Select
            name="month"
            label={m.ai_costs_month_label()}
            placeholder={m.ai_costs_month_label()}
            value={picked ?? current}
            onChange={setPicked}
            groups={[
              {
                id: "months",
                options: [...months].reverse().map((entry) => ({
                  id: entry.month,
                  label: formatMonth(entry.month),
                })),
              },
            ]}
          />
        </Form>
      ) : null}
      {summary.isPending ? <p role="status">{m.ai_costs_loading()}</p> : null}
      {summary.isError && !monthError ? (
        <LoadFailure message={m.ai_costs_load_failed()} error={summary.error} onRetry={summary.refetch} />
      ) : null}
      {summary.data ? <Summary summary={summary.data} /> : null}
      <section aria-labelledby="ai-costs-history-heading" className="flex flex-col gap-2">
        <h3 id="ai-costs-history-heading" className="text-h3">
          {m.ai_costs_history()}
        </h3>
        {history.isPending ? <p role="status">{m.ai_costs_loading()}</p> : null}
        {history.isError ? (
          <LoadFailure
            message={m.ai_costs_history_failed()}
            error={history.error}
            onRetry={history.refetch}
          />
        ) : null}
        {history.isSuccess ? <CostHistoryTable history={months} /> : null}
      </section>
    </div>
  );
}

interface LoadFailureProps {
  message: string;
  error: unknown;
  onRetry: () => void;
}

function LoadFailure({ message, error, onRetry }: LoadFailureProps) {
  return (
    <div className="flex flex-col items-start gap-3">
      <Alert tone="error" title={message}>
        <p>{describeSetupError(error).message}</p>
      </Alert>
      <Button variant="secondary" onPress={onRetry}>
        {m.error_retry()}
      </Button>
    </div>
  );
}

function Summary({ summary }: { summary: CostSummaryResponse }) {
  const month = formatMonth(summary.month);
  if (summary.total.calls === 0) return <p>{m.ai_costs_empty({ month })}</p>;
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <SpentAgainstCap summary={summary} month={month} />
        <p className="text-muted">
          {m.ai_costs_totals({
            count: summary.total.calls,
            shown: formatCount(summary.total.calls),
            tokensIn: formatCount(summary.total.inputTokens),
            tokensOut: formatCount(summary.total.outputTokens),
          })}
        </p>
        {summary.total.unknownCostCalls > 0 ? (
          <p className="text-muted">{unpricedText(summary.total.unknownCostCalls)}</p>
        ) : null}
      </div>
      <CostBreakdowns summary={summary} />
    </div>
  );
}

/** Progress with text (never colour alone) against the cap; without a cap, or for a past month, just the sum. */
function SpentAgainstCap({ summary, month }: { summary: CostSummaryResponse; month: string }) {
  const { budget } = summary;
  if (budget?.capMicros != null) {
    const percent = budgetPercent(budget.spentMicros, budget.capMicros);
    const text = m.ai_costs_budget_text({
      spent: formatUsd(budget.spentMicros),
      cap: formatUsd(budget.capMicros),
      percent: formatPercent(percent),
    });
    const reached = budget.state === "REACHED";
    return (
      <>
        <p className="text-h3">{text}</p>
        <ProgressBar
          label={m.ai_costs_budget_label()}
          percent={percent}
          valueText={text}
          tone={reached ? "critical" : "normal"}
        />
        {reached ? <p className="font-semibold">{m.ai_budget_reached_title()}</p> : null}
      </>
    );
  }
  const { knownMicros } = costFigure(summary.total);
  return (
    <>
      <p className="text-h3">
        {m.ai_costs_spent({
          month,
          spent: knownMicros === null ? m.ai_costs_unknown() : formatUsd(knownMicros),
        })}
      </p>
      {budget ? <p className="text-muted">{m.ai_costs_no_cap()}</p> : null}
    </>
  );
}
