// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotSummary
import io.github.scriptibus.jofi.applications.domain.SourceId
import java.time.Instant

/**
 * Stores the job description history (table `application_description_snapshot`, implemented with #86).
 * Snapshots are append-only: a new text is a new row, and the only change a stored snapshot ever takes is
 * being frozen once (a trigger, `application_description_snapshot_immutable`, rejects anything else). They go
 * with their source (`ON DELETE CASCADE`). The use case appends the changelog entry (entity
 * [SnapshotId.ENTITY_TYPE]; the reason and hash, never the text) in the same transaction. Implementations never
 * throw and never log row data. An insert whose source is gone (`application_description_snapshot_source_fk`,
 * by name) is [ApplicationStoreResult.NotFound].
 */
interface DescriptionSnapshotRepositoryPort {
    /** Stores a new snapshot ([DescriptionSnapshot.next], never a frozen one). */
    fun add(snapshot: DescriptionSnapshot): ApplicationStoreResult<Unit>

    /**
     * The newest snapshot of [source] (by capture time), `null` if it has none. Locks the source's row until the
     * transaction ends, so two recordings for one source cannot both store the same new text.
     */
    fun latest(source: SourceId): ApplicationStoreResult<DescriptionSnapshot?>

    /** The versions of [source], oldest first, without their texts. */
    fun listBySource(source: SourceId): ApplicationStoreResult<List<SnapshotSummary>>

    /** The snapshot [id] of one of [application]'s sources; [ApplicationStoreResult.NotFound] otherwise. */
    fun findById(
        application: ApplicationId,
        id: SnapshotId,
    ): ApplicationStoreResult<DescriptionSnapshot>

    /**
     * Freezes what the user applied for (ADR-0046), as [DescriptionSnapshot.toFreeze] decides per source: for each
     * source of [application] **without a frozen snapshot** (`NOT EXISTS (… frozen_at IS NOT NULL)`; only the first
     * freeze counts, also after a reopening), its newest snapshot captured at or before [asOf] gets `frozen_at =`
     * [asOf]. Returns the ids it froze (none if nothing was left to freeze), for the changelog; repeating it freezes
     * nothing new. Called synchronously, in the same transaction as the status change that
     * `ApplicationStatusChanged.freezesDescriptions` (#84's `ChangeApplicationStatusUseCase`, [asOf] = the change's
     * time), so no freeze can be lost between a commit and an event listener.
     */
    fun freeze(
        application: ApplicationId,
        asOf: Instant,
    ): ApplicationStoreResult<List<SnapshotId>>
}
