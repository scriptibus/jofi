// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port

import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import java.time.Instant

/**
 * The append-only AI cost meter (table `ai_cost_entry`; written by the AI gateway in #20, read by
 * the cost summaries in #24). Never throws.
 */
interface CostEntryPort {
    fun append(entry: CostEntry): SetupStoreResult<Unit>

    /** Entries with `from <= occurredAt < until`, oldest first. */
    fun findBetween(
        from: Instant,
        until: Instant,
    ): SetupStoreResult<List<CostEntry>>

    /**
     * The sum of the known estimated costs of the entries with `from <= occurredAt < until`, in USD;
     * entries with an unknown cost add nothing. The budget check reads it before non-essential calls.
     */
    fun totalBetween(
        from: Instant,
        until: Instant,
    ): SetupStoreResult<Money>
}
