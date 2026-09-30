// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.scriptibus.jofi.setup.domain.BudgetStatus
import io.github.scriptibus.jofi.setup.domain.BudgetUsage
import io.github.scriptibus.jofi.setup.domain.CostSummary
import io.github.scriptibus.jofi.setup.domain.CostTotals
import io.github.scriptibus.jofi.setup.domain.MonthlyCostTotals
import java.time.Instant

// Amounts are integer micros (millionths) of a US dollar (ADR-0032), exact in JSON and in JavaScript
// up to far beyond any AI bill; the client formats them.

/**
 * What a set of AI calls used and cost. [knownCostMicros] sums the calls with a known cost only;
 * [unknownCostCalls] counts the others (no price for the model, or no usage reported), whose cost is
 * never guessed, so with unknown calls the cost is a lower bound.
 */
data class CostTotalsResponse(
    val calls: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val knownCostMicros: Long,
    val unknownCostCalls: Long,
) {
    companion object {
        fun from(totals: CostTotals): CostTotalsResponse =
            CostTotalsResponse(
                calls = totals.calls,
                inputTokens = totals.usage.inputTokens,
                outputTokens = totals.usage.outputTokens,
                knownCostMicros = totals.knownCost.micros,
                unknownCostCalls = totals.unknownCostCalls,
            )
    }
}

data class TaskCostResponse(
    val task: AiTaskType,
    val totals: CostTotalsResponse,
)

/** The provider kind is recorded with each call, so costs of a deleted provider still count here. */
data class ProviderKindCostResponse(
    val providerKind: AiProviderType,
    val totals: CostTotalsResponse,
)

data class ModelCostResponse(
    val providerKind: AiProviderType,
    val model: String,
    val totals: CostTotalsResponse,
)

/** Whether this month's known costs have reached the cap. */
enum class BudgetState { NO_CAP, WITHIN_BUDGET, REACHED }

/**
 * This month's spending against the optional cap. At the cap, the [pausedTasks] (scanner pre-scoring)
 * do not run until [pausedUntil], the start of the next UTC month; everything the user triggers keeps
 * working. The cap is soft: calls already running finish.
 */
data class MonthlyBudgetResponse(
    val month: String,
    val capMicros: Long?,
    val spentMicros: Long,
    val remainingMicros: Long?,
    val state: BudgetState,
    val pausedTasks: List<AiTaskType>,
    val pausedUntil: Instant?,
    val currency: String,
) {
    companion object {
        fun from(usage: BudgetUsage): MonthlyBudgetResponse {
            val paused = usage.pausedTasks.map(AiTaskType::from).sorted()
            return MonthlyBudgetResponse(
                month = usage.month.toString(),
                capMicros = usage.budget?.cap?.micros,
                spentMicros = usage.spent.micros,
                remainingMicros = usage.remaining?.micros,
                state =
                    when (usage.status) {
                        null -> BudgetState.NO_CAP
                        BudgetStatus.WithinBudget -> BudgetState.WITHIN_BUDGET
                        else -> BudgetState.REACHED
                    },
                pausedTasks = paused,
                pausedUntil = usage.month.end.takeIf { paused.isNotEmpty() },
                currency = usage.spent.currency.currencyCode,
            )
        }
    }
}

/** A month's AI costs; [budget] is present for the current month only (the cap has no history). */
data class CostSummaryResponse(
    val month: String,
    val currency: String,
    val total: CostTotalsResponse,
    val byTask: List<TaskCostResponse>,
    val byProviderKind: List<ProviderKindCostResponse>,
    val byModel: List<ModelCostResponse>,
    val budget: MonthlyBudgetResponse?,
) {
    companion object {
        fun from(summary: CostSummary): CostSummaryResponse {
            val costs = summary.costs
            return CostSummaryResponse(
                month = costs.month.toString(),
                currency = costs.total.knownCost.currency.currencyCode,
                total = CostTotalsResponse.from(costs.total),
                byTask =
                    costs.byTask.map { (task, totals) ->
                        TaskCostResponse(AiTaskType.from(task), CostTotalsResponse.from(totals))
                    },
                byProviderKind =
                    costs.byProviderKind.map { (kind, totals) ->
                        ProviderKindCostResponse(AiProviderType.from(kind), CostTotalsResponse.from(totals))
                    },
                byModel =
                    costs.byModel.map { (key, totals) ->
                        ModelCostResponse(
                            AiProviderType.from(key.providerKind),
                            key.model.value,
                            CostTotalsResponse.from(totals),
                        )
                    },
                budget = summary.budget?.let(MonthlyBudgetResponse::from),
            )
        }
    }
}

data class MonthlyCostResponse(
    val month: String,
    val totals: CostTotalsResponse,
) {
    companion object {
        fun from(month: MonthlyCostTotals): MonthlyCostResponse =
            MonthlyCostResponse(month.month.toString(), CostTotalsResponse.from(month.totals))
    }
}

/**
 * The new cap in micros of a US dollar (e.g. `25000000` for 25 USD), or an explicit null to remove the
 * cap. The field is required: a body without it is refused, so a truncated or empty request never
 * lifts the cap by accident.
 */
data class SetMonthlyBudgetRequest(
    @param:JsonProperty(required = true)
    val capMicros: Long?,
)
