// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.application.port.inbound.GetCostHistoryPort
import io.github.scriptibus.jofi.setup.domain.BillingMonth
import io.github.scriptibus.jofi.setup.domain.CostHistory
import io.github.scriptibus.jofi.setup.domain.CostTotals
import io.github.scriptibus.jofi.setup.domain.MonthlyCostTotals
import io.github.scriptibus.jofi.setup.domain.SetupResult
import java.time.Clock

/** The AI cost totals of the last months (spec §3.2), oldest first; a month without calls shows zero. */
class GetCostHistoryUseCase(
    private val costs: CostEntryPort,
    private val clock: Clock,
) : GetCostHistoryPort {
    override fun execute(months: Int): SetupResult<List<MonthlyCostTotals>> =
        CostHistory.months(months, BillingMonth.of(clock.instant())).toSetupResult().then { range ->
            costs.totalsByMonth(range.first(), range.last()).toSetupResult().then { totals ->
                SetupResult.Success(range.map { MonthlyCostTotals(it, totals[it] ?: CostTotals.NONE) })
            }
        }
}
