// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage

/**
 * What a set of AI calls used and cost (spec §3.2). [knownCost] sums only the calls whose cost is
 * known; [unknownCostCalls] counts the others, whose cost is never guessed (ADR-0043). With unknown
 * calls, [knownCost] is therefore a lower bound. Tokens are counted for every call.
 */
data class CostTotals(
    val calls: Long,
    val usage: TokenUsage,
    val knownCost: Money,
    val unknownCostCalls: Long,
) {
    init {
        require(calls >= 0) { "A number of calls must not be negative" }
        require(unknownCostCalls in 0..calls) { "Calls with an unknown cost are part of all calls" }
        require(knownCost.isAccountingCurrency) { "Costs are reported in ${Money.ACCOUNTING_CURRENCY}" }
    }

    operator fun plus(other: CostTotals): CostTotals =
        CostTotals(
            Math.addExact(calls, other.calls),
            TokenUsage(
                Math.addExact(usage.inputTokens, other.usage.inputTokens),
                Math.addExact(usage.outputTokens, other.usage.outputTokens),
            ),
            knownCost + other.knownCost,
            Math.addExact(unknownCostCalls, other.unknownCostCalls),
        )

    companion object {
        /** No calls at all. */
        val NONE: CostTotals = CostTotals(0, TokenUsage(0, 0), Money.usd(0), 0)
    }
}

/**
 * A model as costs are reported. Names are only unique within a provider kind, and the kind is the
 * snapshot in the cost entry, so costs stay attributed after the provider config is deleted.
 */
data class ModelKey(
    val providerKind: ProviderKind,
    val model: ModelName,
) : Comparable<ModelKey> {
    override fun compareTo(other: ModelKey): Int = compareValuesBy(this, other, { it.providerKind }, { it.model.value })
}

/** The totals of one [task] on one [model] in a period, as the store sums them. */
data class CostGroup(
    val task: AiTask,
    val model: ModelKey,
    val totals: CostTotals,
)

/**
 * A month's costs in total and per task, per provider kind and per model (spec §3.2). Each breakdown
 * lists only what was used, in a fixed order (task order, provider kind order, then model name).
 */
data class CostBreakdown(
    val month: BillingMonth,
    val total: CostTotals,
    val byTask: Map<AiTask, CostTotals>,
    val byProviderKind: Map<ProviderKind, CostTotals>,
    val byModel: Map<ModelKey, CostTotals>,
) {
    companion object {
        fun of(
            month: BillingMonth,
            groups: List<CostGroup>,
        ): CostBreakdown =
            CostBreakdown(
                month = month,
                total = groups.fold(CostTotals.NONE) { sum, group -> sum + group.totals },
                byTask = sumBy(groups) { it.task },
                byProviderKind = sumBy(groups) { it.model.providerKind },
                byModel = sumBy(groups) { it.model },
            )

        private fun <K : Comparable<K>> sumBy(
            groups: List<CostGroup>,
            key: (CostGroup) -> K,
        ): Map<K, CostTotals> =
            groups
                .groupingBy(key)
                .fold(CostTotals.NONE) { sum, group -> sum + group.totals }
                .toSortedMap()
    }
}

/** A month's costs with the budget; [budget] is set only for the current month, the one the cap applies to. */
data class CostSummary(
    val costs: CostBreakdown,
    val budget: BudgetUsage?,
)

/** The totals of one [month] in the cost history. */
data class MonthlyCostTotals(
    val month: BillingMonth,
    val totals: CostTotals,
)

/** The months a cost history covers. */
object CostHistory {
    /** Two years: enough to compare a month with the year before, and a bounded query. */
    const val MAX_MONTHS = 24

    /** The [count] months up to and including [current], oldest first, if [count] is 1 to [MAX_MONTHS]. */
    fun months(
        count: Int,
        current: BillingMonth,
    ): SetupValidation<List<BillingMonth>> =
        if (count in 1..MAX_MONTHS) {
            SetupValidation.Valid((count - 1 downTo 0).map { current.minus(it.toLong()) })
        } else {
            SetupValidation.Invalid(listOf(SetupViolation(SetupField.MONTHS, SetupViolationKind.OUT_OF_RANGE)))
        }
}
