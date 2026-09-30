// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_DESCRIPTION_SNAPSHOT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SOURCE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationRecord
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import org.jooq.DSLContext
import java.util.UUID

/** The statements `ApplicationRepository` combines, in the caller's transaction; they throw, the repository maps. */
internal class ApplicationTables(
    private val dsl: DSLContext,
) {
    /**
     * Writes [record]'s columns only if the stored version is one below [application]'s, then runs
     * [andThen]; otherwise tells a stale version from a missing application.
     */
    fun versioned(
        application: Application,
        record: ApplicationRecord,
        andThen: () -> Unit,
    ): ApplicationStoreResult<Unit> {
        val updated =
            dsl
                .update(APPLICATION)
                .set(record)
                .where(APPLICATION.ID.eq(application.id.value))
                .and(APPLICATION.VERSION.eq(application.version - 1))
                .execute()
        return when {
            updated == 1 -> ApplicationStoreResult.Success(Unit).also { andThen() }
            exists(application.id) -> ApplicationStoreResult.VersionConflict
            else -> ApplicationStoreResult.NotFound
        }
    }

    fun exists(id: ApplicationId): Boolean = dsl.fetchExists(APPLICATION, APPLICATION.ID.eq(id.value))

    fun linksOf(id: ApplicationId): Set<ContactRef> = linksOf(listOf(id))[id.value].orEmpty()

    /** The contact links of each of [ids], by application id (absent: none). */
    fun linksOf(ids: Collection<ApplicationId>): Map<UUID, Set<ContactRef>> =
        dsl
            .select(APPLICATION_CONTACT.APPLICATION_ID, APPLICATION_CONTACT.CONTACT_ID)
            .from(APPLICATION_CONTACT)
            .where(APPLICATION_CONTACT.APPLICATION_ID.`in`(ids.map { it.value }))
            .fetchGroups(APPLICATION_CONTACT.APPLICATION_ID, APPLICATION_CONTACT.CONTACT_ID)
            .mapValues { (_, contacts) -> contacts.mapTo(mutableSetOf(), ::ContactRef) }

    fun insertLinks(
        id: ApplicationId,
        contacts: Set<ContactRef>,
    ) {
        if (contacts.isEmpty()) return
        val insert =
            dsl.insertInto(
                APPLICATION_CONTACT,
                APPLICATION_CONTACT.APPLICATION_ID,
                APPLICATION_CONTACT.CONTACT_ID,
            )
        contacts.fold(insert) { statement, contact -> statement.values(id.value, contact.value) }.execute()
    }

    fun appendHistory(change: StatusChange) {
        dsl.insertInto(APPLICATION_STATUS_CHANGE).set(StatusChangeRecords.toRecord(change)).execute()
    }

    /** Rewrites the links of [id] to exactly [contacts], only if the stored set differs. */
    fun replaceLinksIfChanged(
        id: ApplicationId,
        contacts: Set<ContactRef>,
    ) {
        if (linksOf(id) == contacts) return
        dsl.deleteFrom(APPLICATION_CONTACT).where(APPLICATION_CONTACT.APPLICATION_ID.eq(id.value)).execute()
        insertLinks(id, contacts)
    }

    /** The status history of [id], oldest entry first (the identity is the order of appending). */
    fun history(id: ApplicationId): List<StatusChange> =
        dsl
            .selectFrom(APPLICATION_STATUS_CHANGE)
            .where(APPLICATION_STATUS_CHANGE.APPLICATION_ID.eq(id.value))
            .orderBy(APPLICATION_STATUS_CHANGE.ID)
            .fetch()
            .map(StatusChangeRecords::toDomain)

    /** The sources of [id], oldest first (their own port writes them, #78). */
    fun sourcesOf(id: ApplicationId): List<ApplicationSource> = sourcesOf(listOf(id))[id.value].orEmpty()

    /** The sources of each of [ids], oldest first, by application id (absent: none). */
    fun sourcesOf(ids: Collection<ApplicationId>): Map<UUID, List<ApplicationSource>> =
        dsl
            .selectFrom(APPLICATION_SOURCE)
            .where(APPLICATION_SOURCE.APPLICATION_ID.`in`(ids.map { it.value }))
            .orderBy(APPLICATION_SOURCE.DISCOVERED_AT, APPLICATION_SOURCE.ID)
            .fetch { source ->
                ApplicationSource(
                    SourceId(source.id),
                    ApplicationId(source.applicationId),
                    SourceKind.valueOf(source.kind),
                    source.originalUrl?.let(::WebAddress),
                    source.discoveredAt.toInstant(),
                    source.offlineSince?.toInstant(),
                )
            }.groupBy { it.application.value }

    /** The description snapshots of all sources of [id]. */
    fun snapshotCount(id: ApplicationId): Int =
        dsl.fetchCount(
            dsl
                .select(APPLICATION_DESCRIPTION_SNAPSHOT.ID)
                .from(APPLICATION_DESCRIPTION_SNAPSHOT)
                .join(APPLICATION_SOURCE)
                .on(APPLICATION_SOURCE.ID.eq(APPLICATION_DESCRIPTION_SNAPSHOT.SOURCE_ID))
                .where(APPLICATION_SOURCE.APPLICATION_ID.eq(id.value)),
        )
}
