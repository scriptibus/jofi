// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.application.port.inbound.SetMonthlyBudgetPort
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.BudgetUsage
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.setup.domain.SetupValidation
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Sets or removes the monthly AI budget cap (spec §3.2). Only the user may change it: the AI or a
 * scanner raising its own cap would defeat it. Raising, lowering or removing the cap destroys nothing
 * (the cap only pauses scanner pre-scoring), so no confirmation step is needed; every change lands in
 * the changelog in the same transaction. The answer is this month's usage under the new cap.
 */
class SetMonthlyBudgetUseCase(
    private val budgets: MonthlyBudgetPort,
    private val costs: CostEntryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : SetMonthlyBudgetPort {
    override fun execute(
        capMicros: Long?,
        actor: Actor,
    ): SetupResult<BudgetUsage> =
        asUser(actor) {
            val requested = if (capMicros == null) SetupValidation.Valid(null) else MonthlyBudget.validate(capMicros)
            requested
                .toSetupResult()
                .then { budget ->
                    budgets.find().orNull().then { before -> change(before, budget, actor) }
                }.then { budgetUsage(BillingMonth.of(clock.instant()), costs, budgets) }
        }

    /** Stores [after] unless it equals [before]: an unchanged cap writes nothing. */
    private fun change(
        before: MonthlyBudget?,
        after: MonthlyBudget?,
        actor: Actor,
    ): SetupResult<Unit> =
        if (after == before) SetupResult.Success(Unit) else transactions.whenSuccessful { store(before, after, actor) }

    private fun store(
        before: MonthlyBudget?,
        after: MonthlyBudget?,
        actor: Actor,
    ): SetupResult<Unit> {
        val stored = if (after == null) budgets.clear() else budgets.save(after)
        val change = changeOf("capUsd", before?.cap?.amount?.toPlainString(), after?.cap?.amount?.toPlainString())
        val description = if (after == null) "Removed the monthly AI budget" else "Set the monthly AI budget"
        return when {
            stored !is SetupStoreResult.Success -> {
                SetupResult.StorageFailure("save budget")
            }

            !changelog.record(
                MonthlyBudget.ENTITY_REF,
                actor,
                clock.storedNow(),
                description,
                listOfNotNull(change),
            ) -> {
                SetupResult.StorageFailure("changelog")
            }

            else -> {
                SetupResult.Success(Unit)
            }
        }
    }
}
