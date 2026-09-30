// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotSummary
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_DESCRIPTION_SNAPSHOT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SOURCE
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.Select
import org.jooq.impl.DSL
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The job description history (`application_description_snapshot`), in the caller's transaction. Only
 * [freeze] exists yet: the status change needs it (#84, ADR-0046). Recording, listing and reading snapshots
 * are #86; until then their endpoints answer 501 and nothing calls those methods, so they answer a storage
 * failure. Posting text is untrusted and may be long, so only operations and exception types are logged.
 */
@Component
class DescriptionSnapshotRepository(
    private val dsl: DSLContext,
) : DescriptionSnapshotRepositoryPort {
    override fun add(snapshot: DescriptionSnapshot): ApplicationStoreResult<Unit> =
        ApplicationStoreResult.StorageFailure("add snapshot")

    override fun latest(source: SourceId): ApplicationStoreResult<DescriptionSnapshot?> =
        ApplicationStoreResult.StorageFailure("latest snapshot")

    override fun listBySource(source: SourceId): ApplicationStoreResult<List<SnapshotSummary>> =
        ApplicationStoreResult.StorageFailure("list snapshots")

    override fun findById(
        application: ApplicationId,
        id: SnapshotId,
    ): ApplicationStoreResult<DescriptionSnapshot> = ApplicationStoreResult.StorageFailure("find snapshot")

    /**
     * One statement, as `DescriptionSnapshot.toFreeze` decides per source: of each source of [application]
     * without a frozen snapshot, the newest snapshot captured by [asOf] (ties by id, so exactly one per source).
     */
    override fun freeze(
        application: ApplicationId,
        asOf: Instant,
    ): ApplicationStoreResult<List<SnapshotId>> =
        try {
            val snapshot = APPLICATION_DESCRIPTION_SNAPSHOT
            val at = asOf.atOffset(ZoneOffset.UTC)
            val ids =
                dsl
                    .update(snapshot)
                    .set(snapshot.FROZEN_AT, at)
                    .where(snapshot.ID.`in`(newestUnfrozen(application, at)))
                    .returning(snapshot.ID)
                    .fetch(snapshot.ID)
            ApplicationStoreResult.Success(ids.map(::SnapshotId))
        } catch (exception: RuntimeException) {
            log.error("Description snapshot store freeze failed: {}", exception.javaClass.name)
            ApplicationStoreResult.StorageFailure("freeze")
        }

    /** Per source of [application] without a frozen snapshot, the id of its newest snapshot captured by [at]. */
    private fun newestUnfrozen(
        application: ApplicationId,
        at: OffsetDateTime,
    ): Select<Record1<UUID>> {
        val snapshot = APPLICATION_DESCRIPTION_SNAPSHOT
        val frozen = APPLICATION_DESCRIPTION_SNAPSHOT.`as`("frozen")
        return dsl
            .select(snapshot.ID)
            .distinctOn(snapshot.SOURCE_ID)
            .from(snapshot)
            .join(APPLICATION_SOURCE)
            .on(APPLICATION_SOURCE.ID.eq(snapshot.SOURCE_ID))
            .where(APPLICATION_SOURCE.APPLICATION_ID.eq(application.value))
            .and(snapshot.CAPTURED_AT.le(at))
            .andNotExists(
                DSL
                    .selectOne()
                    .from(frozen)
                    .where(frozen.SOURCE_ID.eq(snapshot.SOURCE_ID))
                    .and(frozen.FROZEN_AT.isNotNull),
            ).orderBy(snapshot.SOURCE_ID, snapshot.CAPTURED_AT.desc(), snapshot.ID.desc())
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(DescriptionSnapshotRepository::class.java)
    }
}
