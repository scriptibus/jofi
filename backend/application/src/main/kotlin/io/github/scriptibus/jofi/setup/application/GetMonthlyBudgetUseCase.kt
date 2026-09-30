// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.application.port.inbound.GetMonthlyBudgetPort
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.BudgetUsage
import io.github.scriptibus.jofi.setup.domain.SetupResult
import java.time.Clock

/** The monthly budget cap, this month's spending against it and whether non-essential AI work is paused. */
class GetMonthlyBudgetUseCase(
    private val costs: CostEntryPort,
    private val budgets: MonthlyBudgetPort,
    private val clock: Clock,
) : GetMonthlyBudgetPort {
    override fun execute(): SetupResult<BudgetUsage> = budgetUsage(BillingMonth.of(clock.instant()), costs, budgets)
}
