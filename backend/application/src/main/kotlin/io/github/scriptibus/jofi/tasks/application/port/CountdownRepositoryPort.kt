// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application.port

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult

/**
 * Stores custom countdowns (table `countdown`; implemented with the use cases in #112). The use case appends the
 * changelog entry (entity [CountdownId.ENTITY_TYPE]; field names only, never the title) in the same transaction.
 * Implementations never throw and never log row data.
 */
interface CountdownRepositoryPort {
    fun add(countdown: Countdown): TaskStoreResult<Unit>

    /** Stores [countdown] only if the stored version is exactly one below its own, `VersionConflict` otherwise. */
    fun update(countdown: Countdown): TaskStoreResult<Unit>

    fun findById(id: CountdownId): TaskStoreResult<Countdown>

    /** Every custom countdown, soonest target first (then by id). */
    fun list(): TaskStoreResult<List<Countdown>>

    /**
     * Deletes the countdown. The adapter answers [TaskStoreResult.NotConfirmed] unless
     * `proof.covers(Countdown.DELETE_OPERATION, id.value.toString())` (ADR-0039).
     */
    fun delete(
        id: CountdownId,
        proof: ConfirmationResult.Confirmed,
    ): TaskStoreResult<Unit>
}
