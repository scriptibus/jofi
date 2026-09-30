// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.GetMonthlyBudgetUseCase
import io.github.scriptibus.jofi.setup.application.SetMonthlyBudgetUseCase
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The optional monthly AI budget cap (spec §3.2): at the cap, scanner pre-scoring pauses until the next
 * UTC month; everything the user triggers keeps working. Only the logged-in user sets it.
 */
@RestController
@RequestMapping("/api/setup/budget")
class MonthlyBudgetController(
    private val getBudget: GetMonthlyBudgetUseCase,
    private val setBudget: SetMonthlyBudgetUseCase,
) {
    @GetMapping
    fun getMonthlyBudget(): MonthlyBudgetResponse = MonthlyBudgetResponse.from(getBudget.execute().orThrow())

    /**
     * Sets the cap (1 micro to 1,000,000 USD), or removes it with `capMicros: null`. Removing or changing
     * the cap destroys nothing, so there is no confirmation step; the change lands in the changelog.
     */
    @PutMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun setMonthlyBudget(
        @RequestBody request: SetMonthlyBudgetRequest,
    ): MonthlyBudgetResponse = MonthlyBudgetResponse.from(setBudget.execute(request.capMicros, Actor.User).orThrow())
}
