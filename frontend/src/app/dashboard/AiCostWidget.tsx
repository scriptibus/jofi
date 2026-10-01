// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { type CostSummaryResponse, useGetCostSummary } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, ProgressBar, TextLink } from "../../ui";
import { budgetPercent } from "../ai/costs";
import { formatUsd } from "../ai/money";
import { formatInstant, formatPercent } from "../applications/format";
import { formatCount } from "./dashboard";
import { Widget, WidgetContent, widgetQuery } from "./Widget";

/**
 * The AI cost this month against the budget (ADR-0052: the current month's cost summary, UTC months as the
 * budget counts them). Links to Settings > AI, where the budget is set.
 */
export function AiCostWidget() {
  const query = useGetCostSummary(undefined, { query: widgetQuery });
  return (
    <Widget
      id="dashboard-ai-cost"
      title={m.dashboard_ai_cost_heading()}
      footer={
        <TextLink to="/settings" hash="ai-budget-heading">
          {m.dashboard_ai_cost_settings()}
        </TextLink>
      }
    >
      <WidgetContent query={query} failed={m.dashboard_ai_cost_failed()}>
        {(summary) => <AiCost summary={summary} />}
      </WidgetContent>
    </Widget>
  );
}

function AiCost({ summary }: { summary: CostSummaryResponse }) {
  const { budget, total } = summary;
  // The budget's figure is summed exactly as the gateway's budget check sums it; without a budget, the known cost.
  const spentMicros = budget?.spentMicros ?? total.knownCostMicros;
  const spent = formatUsd(spentMicros);
  const cap = budget?.capMicros ?? null;
  const reached = budget?.state === "REACHED";
  return (
    <>
      <p className="flex flex-col gap-1">
        <span className="font-data text-h2">{spent}</span>
        <span className="text-muted">
          {cap === null
            ? m.dashboard_ai_cost_no_cap()
            : m.dashboard_ai_cost_of_cap({
                cap: formatUsd(cap),
                remaining: formatUsd(budget?.remainingMicros ?? 0),
              })}
        </span>
      </p>
      {cap === null ? null : (
        <ProgressBar
          label={m.ai_costs_budget_label()}
          percent={budgetPercent(spentMicros, cap)}
          valueText={m.ai_costs_budget_text({
            spent,
            cap: formatUsd(cap),
            percent: formatPercent(budgetPercent(spentMicros, cap)),
          })}
          tone={reached ? "critical" : "normal"}
        />
      )}
      {reached ? (
        <Alert tone="warning" title={m.dashboard_ai_cost_reached()}>
          {budget?.pausedUntil ? (
            <p>{m.dashboard_ai_cost_paused_until({ until: formatInstant(budget.pausedUntil) })}</p>
          ) : null}
        </Alert>
      ) : null}
      {total.unknownCostCalls > 0 ? (
        <p className="text-muted">
          {m.dashboard_ai_cost_unknown({ count: formatCount(total.unknownCostCalls) })}
        </p>
      ) : null}
    </>
  );
}
