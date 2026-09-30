// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port

import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult

/**
 * Stores the optional monthly budget cap (table `ai_monthly_budget`, at most one row; implemented
 * in #24). Callers of [save] and [clear] append a changelog entry. Never throws.
 */
interface MonthlyBudgetPort {
    /** The cap, or [SetupStoreResult.NotFound] when none is set. */
    fun find(): SetupStoreResult<MonthlyBudget>

    fun save(budget: MonthlyBudget): SetupStoreResult<Unit>

    /** Removes the cap; succeeds when none was set. */
    fun clear(): SetupStoreResult<Unit>
}
