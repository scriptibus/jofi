// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.BudgetUsage
import io.github.scriptibus.jofi.setup.domain.SetupResult

/**
 * The spending of [month] against the stored cap, summed exactly as the AI gateway's budget check sums
 * it (ADR-0043), so the report and the pause always agree.
 */
internal fun budgetUsage(
    month: BillingMonth,
    costs: CostEntryPort,
    budgets: MonthlyBudgetPort,
): SetupResult<BudgetUsage> =
    budgets.find().orNull().then { budget ->
        costs.totalBetween(month.start, month.end).toSetupResult().then { spent ->
            SetupResult.Success(BudgetUsage(month, budget, spent))
        }
    }
