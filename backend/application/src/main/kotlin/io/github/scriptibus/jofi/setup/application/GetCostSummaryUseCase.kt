// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.MonthlyBudgetPort
import io.github.scriptibus.jofi.setup.application.port.inbound.GetCostSummaryPort
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.BudgetUsage
import io.github.scriptibus.jofi.setup.domain.CostBreakdown
import io.github.scriptibus.jofi.setup.domain.CostSummary
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupValidation
import java.time.Clock

/**
 * A month's AI costs per task, provider kind and model (spec §3.2). The current month also carries the
 * budget usage; a past month has none, since the cap has no history and today's cap would misjudge it.
 */
class GetCostSummaryUseCase(
    private val costs: CostEntryPort,
    private val budgets: MonthlyBudgetPort,
    private val clock: Clock,
) : GetCostSummaryPort {
    override fun execute(month: String?): SetupResult<CostSummary> {
        val current = BillingMonth.of(clock.instant())
        val chosen = if (month == null) SetupValidation.Valid(current) else BillingMonth.parse(month, current)
        return chosen.toSetupResult().then { billingMonth ->
            costs.summarizeBetween(billingMonth.start, billingMonth.end).toSetupResult().then { groups ->
                val breakdown = CostBreakdown.of(billingMonth, groups)
                if (billingMonth ==
                    current
                ) {
                    withBudget(breakdown)
                } else {
                    SetupResult.Success(CostSummary(breakdown, null))
                }
            }
        }
    }

    private fun withBudget(breakdown: CostBreakdown): SetupResult<CostSummary> =
        budgets.find().orNull().then { budget ->
            val usage = BudgetUsage(breakdown.month, budget, breakdown.total.knownCost)
            SetupResult.Success(CostSummary(breakdown, usage))
        }
}
