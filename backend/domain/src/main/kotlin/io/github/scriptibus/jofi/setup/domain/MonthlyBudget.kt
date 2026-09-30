// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask

/**
 * The optional monthly spending cap (spec §3.2). When the month's spending reaches [cap],
 * non-essential AI work (scanner pre-scoring) pauses; everything the user triggers keeps working.
 */
data class MonthlyBudget(
    val cap: Money,
) {
    init {
        require(cap.micros > 0) { "A monthly budget must be positive" }
    }

    /** True once [spentThisMonth] has reached the cap. */
    fun isReachedBy(spentThisMonth: Money): Boolean = spentThisMonth >= cap

    /** True when [task] must not run because the cap is reached and the task is non-essential. */
    fun pauses(
        task: AiTask,
        spentThisMonth: Money,
    ): Boolean = task in NON_ESSENTIAL_TASKS && isReachedBy(spentThisMonth)

    companion object {
        /** Background work the user did not trigger; the budget cap pauses it. */
        val NON_ESSENTIAL_TASKS: Set<AiTask> = setOf(AiTask.SCANNER_PRE_SCORING)
    }
}
