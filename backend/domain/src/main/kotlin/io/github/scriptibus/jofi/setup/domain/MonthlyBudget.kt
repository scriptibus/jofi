// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

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
    }
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
