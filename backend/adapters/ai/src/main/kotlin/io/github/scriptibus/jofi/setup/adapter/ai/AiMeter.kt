// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.ModelPricePort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.BudgetDecision
import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.PriceTable
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * The monthly budget check before a call and the cost meter after it (spec §3.2, ADR-0043).
 *
 * Only non-essential tasks ([MonthlyBudget.NON_ESSENTIAL_TASKS]) are checked: at the cap they get
 * [AiResult.BudgetExceeded], and when the budget or the spending cannot be read they do not run
 * either ([AiResult.Unavailable]). Everything the user triggers never waits for the budget.
 * The cap is soft: calls already running when it is reached still finish and are metered.
 *
 * A call's cost comes from the user's price for the model of an OpenAI-compatible provider, which has no
 * list price, else from the verified price table; a model with neither has an unknown cost, never a
 * similar model's price (ADR-0043, ADR-0055).
 */
class AiMeter(
    private val costs: CostEntryPort,
    private val budgets: MonthlyBudgetPort,
    private val prices: PriceTable,
    private val userPrices: ModelPricePort,
    private val clock: Clock,
) {
    /** Null when [task] may run; otherwise the result to return without calling the provider. */
    fun admit(task: AiTask): AiResult<Nothing>? =
        if (task !in MonthlyBudget.NON_ESSENTIAL_TASKS) {
            null
        } else {
            when (val found = budgets.find()) {
                is SetupStoreResult.Success -> against(task, found.value)
                SetupStoreResult.NotFound -> null
                else -> unreadable(task)
            }
        }

    private fun against(
        task: AiTask,
        budget: MonthlyBudget,
    ): AiResult<Nothing>? {
        val month = BillingMonth.of(clock.instant())
        return when (val spent = costs.totalBetween(month.start, month.end)) {
            is SetupStoreResult.Success -> {
                when (budget.decide(task, spent.value)) {
                    BudgetDecision.Run -> null
                    BudgetDecision.Pause -> AiResult.BudgetExceeded(task)
                    is BudgetDecision.CurrencyMismatch -> unreadable(task)
                }
            }

            else -> {
                unreadable(task)
            }
        }
    }

    /**
     * Appends one cost entry. A failed append is logged and swallowed: the call has happened, and
     * its answer is worth more than an exact meter (spec §13, a failed AI call never loses data).
     */
    fun record(
        task: AiTask,
        target: ResolvedModel,
        usage: TokenUsage,
    ) {
        val entry =
            CostEntry(
                task = task,
                provider = target.provider.id,
                providerKind = target.provider.kind,
                model = target.model,
                usage = usage,
                estimatedCost = costOf(target, usage),
                occurredAt = clock.instant(),
            )
        val stored = costs.append(entry)
        if (stored !is SetupStoreResult.Success) {
            log.error("Could not meter an AI call for {} ({}): {}", task, target.provider.kind, stored)
        }
    }

    private fun costOf(
        target: ResolvedModel,
        usage: TokenUsage,
    ): Money? =
        when {
            usage == TokenUsage.NONE -> null
            target.provider.kind == ProviderKind.OPENAI_COMPATIBLE -> userPriceCost(target, usage)
            else -> prices.costOf(target.provider.kind, target.model, usage)
        }

    /** The user's price for the model, or null (unknown) when there is none or it cannot be read. */
    private fun userPriceCost(
        target: ResolvedModel,
        usage: TokenUsage,
    ): Money? =
        when (val found = userPrices.find(target.provider.id, target.model)) {
            is SetupStoreResult.Success -> {
                found.value.costOf(usage)
            }

            SetupStoreResult.NotFound -> {
                null
            }

            else -> {
                log.warn("The price of a {} model could not be read; its cost is unknown", target.provider.kind)
                null
            }
        }

    private fun unreadable(task: AiTask): AiResult<Nothing> {
        log.warn("Budget for {} could not be checked; the non-essential call does not run", task)
        return AiResult.Unavailable
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AiMeter::class.java)
    }
}
