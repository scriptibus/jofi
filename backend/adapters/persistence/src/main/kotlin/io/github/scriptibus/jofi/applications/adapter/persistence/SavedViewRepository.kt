// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewDetails
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SAVED_VIEW
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.SavedViewRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.violatedConstraint
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneOffset.UTC

/**
 * Saved views (`saved_view`, ADR-0050), in the caller's transaction. The filter is written and read by
 * [SavedViewDocument]; a document it cannot read (an unknown format) fails the read as a `StorageFailure`, while one
 * it had to clean comes back `adjusted`. `saved_view_name_unique` is recognised by name.
 */
@Component
class SavedViewRepository(
    private val dsl: DSLContext,
) : SavedViewRepositoryPort {
    override fun add(view: SavedView): ApplicationStoreResult<Unit> =
        storeCall("add view") {
            dsl.insertInto(SAVED_VIEW).set(toRecord(view)).execute()
            ApplicationStoreResult.Success(Unit)
        }

    override fun update(view: SavedView): ApplicationStoreResult<Unit> =
        storeCall("update view") {
            val updated =
                dsl
                    .update(SAVED_VIEW)
                    .set(toRecord(view))
                    .where(SAVED_VIEW.ID.eq(view.id.value))
                    .and(SAVED_VIEW.VERSION.eq(view.version - 1))
                    .execute()
            when {
                updated == 1 -> ApplicationStoreResult.Success(Unit)
                dsl.fetchExists(SAVED_VIEW, SAVED_VIEW.ID.eq(view.id.value)) -> ApplicationStoreResult.VersionConflict
                else -> ApplicationStoreResult.NotFound
            }
        }

    override fun findById(id: SavedViewId): ApplicationStoreResult<SavedView> =
        storeCall("find view") {
            val row = dsl.selectFrom(SAVED_VIEW).where(SAVED_VIEW.ID.eq(id.value)).fetchOne()
            when {
                row == null -> ApplicationStoreResult.NotFound
                else -> toView(row)?.let { ApplicationStoreResult.Success(it) } ?: unreadable()
            }
        }

    override fun list(): ApplicationStoreResult<List<SavedView>> =
        storeCall("list views") {
            val views =
                dsl
                    .selectFrom(SAVED_VIEW)
                    .orderBy(SAVED_VIEW.NAME, SAVED_VIEW.ID)
                    .fetch()
                    .map(::toView)
            if (views.any { it == null }) unreadable() else ApplicationStoreResult.Success(views.filterNotNull())
        }

    override fun delete(
        id: SavedViewId,
        proof: ConfirmationResult.Confirmed,
    ): ApplicationStoreResult<Unit> {
        if (!proof.covers(SavedView.DELETE_OPERATION, id.value.toString())) return ApplicationStoreResult.NotConfirmed
        return storeCall("delete view") {
            val deleted = dsl.deleteFrom(SAVED_VIEW).where(SAVED_VIEW.ID.eq(id.value)).execute()
            if (deleted == 0) ApplicationStoreResult.NotFound else ApplicationStoreResult.Success(Unit)
        }
    }

    private fun toRecord(view: SavedView): SavedViewRecord =
        SavedViewRecord().apply {
            id = view.id.value
            name = view.details.name
            filter = SavedViewDocument.write(view.details.filter)
            filterVersion = SavedViewDocument.VERSION
            version = view.version
            createdAt = view.createdAt.atOffset(UTC)
            updatedAt = view.updatedAt.atOffset(UTC)
        }

    /** The view, or null if its filter document cannot be read (a newer format, broken JSON). */
    private fun toView(row: SavedViewRecord): SavedView? =
        SavedViewDocument.read(row.filterVersion, row.filter)?.let { restored ->
            SavedView(
                SavedViewId(row.id),
                SavedViewDetails(row.name, restored.filter),
                row.version,
                row.createdAt.toInstant(),
                row.updatedAt.toInstant(),
                restored.adjusted,
            )
        }

    private fun <T> unreadable(): ApplicationStoreResult<T> {
        log.error("Saved view store found a filter document it cannot read")
        return ApplicationStoreResult.StorageFailure("read view filter")
    }

    /** No exception crosses the port; messages can carry names or filters, so only the exception type is logged. */
    private fun <T> storeCall(
        operation: String,
        block: () -> ApplicationStoreResult<T>,
    ): ApplicationStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            if (exception.violatedConstraint() == SAVED_VIEW_NAME_UNIQUE) {
                ApplicationStoreResult.ViewNameTaken
            } else {
                log.error("Saved view store {} failed: {}", operation, exception.javaClass.name)
                ApplicationStoreResult.StorageFailure(operation)
            }
        }

    private companion object {
        /** Another view has exactly this name (a race past the use case's check, which ignores case). */
        const val SAVED_VIEW_NAME_UNIQUE = "saved_view_name_unique"
        val log: Logger = LoggerFactory.getLogger(SavedViewRepository::class.java)
    }
}
