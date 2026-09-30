// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port.inbound

import io.github.scriptibus.jofi.setup.domain.BudgetUsage
import io.github.scriptibus.jofi.setup.domain.CostSummary
import io.github.scriptibus.jofi.setup.domain.MonthlyCostTotals
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.shared.domain.Actor

// Inbound ports of the AI cost reports and the monthly budget (#24, spec §3.2), each implemented by the
// use case of the same name. Amounts are USD (ADR-0032); months are UTC calendar months (ADR-0043).

/**
 * The costs of one month in total and per task, provider kind and model; [month] (`YYYY-MM`) defaults
 * to the current month, which also carries the budget usage.
 */
interface GetCostSummaryPort {
    fun execute(month: String?): SetupResult<CostSummary>
}

/** The totals of the last [months] months including the current one, oldest first, with empty months. */
interface GetCostHistoryPort {
    fun execute(months: Int): SetupResult<List<MonthlyCostTotals>>
}

/** The current month's spending against the cap, and which tasks the cap pauses now. */
interface GetMonthlyBudgetPort {
    fun execute(): SetupResult<BudgetUsage>
}

/**
 * Sets the cap to [capMicros] millionths of a US dollar, or removes it when null. Only the user may
 * change it (the AI pausing its own budget would be a way around it); every change writes a changelog
 * entry, an unchanged cap writes nothing.
 */
interface SetMonthlyBudgetPort {
    fun execute(
        capMicros: Long?,
        actor: Actor,
    ): SetupResult<BudgetUsage>
}
