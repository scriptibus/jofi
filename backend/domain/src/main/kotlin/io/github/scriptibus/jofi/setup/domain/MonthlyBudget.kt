// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import java.util.Currency

/**
 * The optional monthly spending cap in the accounting currency (USD, ADR-0032; spec §3.2). When
 * the month's spending reaches [cap], non-essential AI work (scanner pre-scoring) pauses;
 * everything the user triggers keeps working.
 */
data class MonthlyBudget(
    val cap: Money,
) {
    init {
        require(cap.isAccountingCurrency) { "A budget is set in ${Money.ACCOUNTING_CURRENCY}" }
        require(cap.micros > 0) { "A monthly budget must be positive" }
    }

    /** Whether [spentThisMonth] has reached the cap. A foreign currency is reported, not thrown. */
    fun status(spentThisMonth: Money): BudgetStatus =
        when {
            spentThisMonth.currency != cap.currency -> BudgetStatus.CurrencyMismatch(spentThisMonth.currency)
            spentThisMonth.micros >= cap.micros -> BudgetStatus.Reached
            else -> BudgetStatus.WithinBudget
        }

    /** Whether [task] may run given [spentThisMonth]: only non-essential tasks pause at the cap. */
    fun decide(
        task: AiTask,
        spentThisMonth: Money,
    ): BudgetDecision =
        when (val status = status(spentThisMonth)) {
            is BudgetStatus.CurrencyMismatch -> BudgetDecision.CurrencyMismatch(status.actual)
            BudgetStatus.Reached -> if (task in NON_ESSENTIAL_TASKS) BudgetDecision.Pause else BudgetDecision.Run
            BudgetStatus.WithinBudget -> BudgetDecision.Run
        }

    companion object {
        /** Background work the user did not trigger; the budget cap pauses it. */
        val NON_ESSENTIAL_TASKS: Set<AiTask> = setOf(AiTask.SCANNER_PRE_SCORING)

        /** The changelog's entity type of the budget; there is only one, so its id is fixed. */
        const val ENTITY_TYPE = "ai_monthly_budget"

        /** How changelog entries refer to the budget. */
        val ENTITY_REF: EntityRef = EntityRef(ENTITY_TYPE, "monthly")

        /**
         * The highest cap the user can set, one million US dollars: a larger one is a typo, and it keeps
         * the micros far away from overflowing.
         */
        val MAX_CAP: Money = Money.usd(1_000_000_000_000)

        /** A cap of [capMicros] millionths of a US dollar, if it is positive and at most [MAX_CAP]. */
        fun validate(capMicros: Long): SetupValidation<MonthlyBudget> =
            if (capMicros in 1..MAX_CAP.micros) {
                SetupValidation.Valid(MonthlyBudget(Money.usd(capMicros)))
            } else {
                SetupValidation.Invalid(listOf(SetupViolation(SetupField.MONTHLY_CAP, SetupViolationKind.OUT_OF_RANGE)))
            }
    }
}

/**
 * The spending of [month] against the optional [budget] (spec §3.2, §10.1), as the AI gateway applies
 * it (ADR-0043): known costs only, so entries with an unknown cost never count toward the cap.
 */
data class BudgetUsage(
    val month: BillingMonth,
    val budget: MonthlyBudget?,
    val spent: Money,
) {
    init {
        require(spent.isAccountingCurrency) { "Spending is measured in ${Money.ACCOUNTING_CURRENCY}" }
    }

    /** Whether the cap is reached; null without a cap. */
    val status: BudgetStatus? get() = budget?.status(spent)

    /** What is left until the cap, never below zero; null without a cap. */
    val remaining: Money? get() = budget?.let { Money.usd(maxOf(0, it.cap.micros - spent.micros)) }

    /** The tasks the gateway pauses now; the pause lifts when the next month starts (at [BillingMonth.end]). */
    val pausedTasks: Set<AiTask>
        get() = if (status == BudgetStatus.Reached) MonthlyBudget.NON_ESSENTIAL_TASKS else emptySet()
}

/** Spending measured against a [MonthlyBudget]. */
sealed interface BudgetStatus {
    data object WithinBudget : BudgetStatus

    data object Reached : BudgetStatus

    /** The spending was given in [actual] instead of the accounting currency; nothing was compared. */
    data class CurrencyMismatch(
        val actual: Currency,
    ) : BudgetStatus
}

/** Whether an AI task may run under the budget. */
sealed interface BudgetDecision {
    data object Run : BudgetDecision

    /** The cap is reached and the task is non-essential. */
    data object Pause : BudgetDecision

    /** The spending was given in [actual] instead of the accounting currency; the caller must fix its input. */
    data class CurrencyMismatch(
        val actual: Currency,
    ) : BudgetDecision
}
