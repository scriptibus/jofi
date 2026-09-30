// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/**
 * Stores saved views of the application list (table `saved_view`; implemented with the use cases in #99). The filter
 * is a versioned JSON document (`SavedViewDocument` in the persistence adapter, ADR-0050), read back through
 * `SavedViewFilter.restore`, so a view whose filter today's rules refuse comes back `adjusted` instead of failing.
 * The use case appends the changelog entry (entity [SavedViewId.ENTITY_TYPE]) in the same transaction.
 * Implementations never throw and never log row data. `saved_view_name_unique` is mapped **by name** to
 * [ApplicationStoreResult.ViewNameTaken]; anything else is a `StorageFailure`.
 */
interface SavedViewRepositoryPort {
    fun add(view: SavedView): ApplicationStoreResult<Unit>

    /** Stores [view] only if the stored version is exactly one below its own, `VersionConflict` otherwise. */
    fun update(view: SavedView): ApplicationStoreResult<Unit>

    fun findById(id: SavedViewId): ApplicationStoreResult<SavedView>

    /** Every saved view by name (then by id); the name order is only for display. */
    fun list(): ApplicationStoreResult<List<SavedView>>

    /**
     * Deletes the view. The adapter answers [ApplicationStoreResult.NotConfirmed] unless
     * `proof.covers(SavedView.DELETE_OPERATION, id.value.toString())` (ADR-0039).
     */
    fun delete(
        id: SavedViewId,
        proof: ConfirmationResult.Confirmed,
    ): ApplicationStoreResult<Unit>
}
