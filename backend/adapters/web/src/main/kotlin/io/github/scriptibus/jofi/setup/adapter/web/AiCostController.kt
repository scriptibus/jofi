// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.GetCostHistoryUseCase
import io.github.scriptibus.jofi.setup.application.GetCostSummaryUseCase
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Tokens and estimated AI costs per task, provider kind, model and month (spec §3.2). Months are UTC
 * calendar months; costs are USD micros; calls with an unknown cost are counted, never priced.
 */
@RestController
@RequestMapping("/api/setup/costs")
class AiCostController(
    private val getSummary: GetCostSummaryUseCase,
    private val getHistory: GetCostHistoryUseCase,
) {
    /** One month (`YYYY-MM`, default the current one; never in the future); the current month adds the budget. */
    @GetMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun getCostSummary(
        @RequestParam(required = false) month: String?,
    ): CostSummaryResponse = CostSummaryResponse.from(getSummary.execute(month).orThrow())

    /** The totals of the last `months` months (1 to 24, default 12) up to the current one, oldest first. */
    @GetMapping("/history")
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun getCostHistory(
        @RequestParam(defaultValue = "12") months: Int,
    ): List<MonthlyCostResponse> = getHistory.execute(months).orThrow().map(MonthlyCostResponse::from)
}
