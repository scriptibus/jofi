// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.ContentHash
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SnapshotSummary
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_DESCRIPTION_SNAPSHOT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SOURCE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationDescriptionSnapshotRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.violatedConstraint
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
 * The job description history (`application_description_snapshot`), in the caller's transaction (ADR-0046).
 * Posting text is untrusted and may be long, so only operations and exception types are logged, never rows.
 */
@Component
class DescriptionSnapshotRepository(
    private val dsl: DSLContext,
) : DescriptionSnapshotRepositoryPort {
    override fun add(snapshot: DescriptionSnapshot): ApplicationStoreResult<Unit> =
        storeCall("add snapshot") {
            dsl.insertInto(APPLICATION_DESCRIPTION_SNAPSHOT).set(toRecord(snapshot)).execute()
            ApplicationStoreResult.Success(Unit)
        }

    /** `FOR NO KEY UPDATE` on the source: recordings of one source wait for each other, snapshot inserts do not. */
    override fun latest(source: SourceId): ApplicationStoreResult<DescriptionSnapshot?> =
        storeCall("latest snapshot") {
            dsl
                .select(APPLICATION_SOURCE.ID)
                .from(APPLICATION_SOURCE)
                .where(APPLICATION_SOURCE.ID.eq(source.value))
                .forNoKeyUpdate()
                .execute()
            val newest =
                dsl
                    .selectFrom(APPLICATION_DESCRIPTION_SNAPSHOT)
                    .where(APPLICATION_DESCRIPTION_SNAPSHOT.SOURCE_ID.eq(source.value))
                    .orderBy(
                        APPLICATION_DESCRIPTION_SNAPSHOT.CAPTURED_AT.desc(),
                        APPLICATION_DESCRIPTION_SNAPSHOT.ID.desc(),
                    ).limit(1)
                    .fetchOne()
            ApplicationStoreResult.Success(newest?.let(::toDomain))
        }

    /** Reads the texts' lengths, never the texts. */
    override fun listBySource(source: SourceId): ApplicationStoreResult<List<SnapshotSummary>> =
        storeCall("list snapshots") {
            val snapshot = APPLICATION_DESCRIPTION_SNAPSHOT
            val length = DSL.charLength(snapshot.DESCRIPTION)
            val summaries =
                dsl
                    .select(
                        snapshot.ID,
                        snapshot.CONTENT_HASH,
                        snapshot.REASON,
                        snapshot.CAPTURED_AT,
                        snapshot.FROZEN_AT,
                        length,
                    ).from(snapshot)
                    .where(snapshot.SOURCE_ID.eq(source.value))
                    .orderBy(snapshot.CAPTURED_AT, snapshot.ID)
                    .fetch { row ->
                        SnapshotSummary(
                            SnapshotId(row.value1()),
                            source,
                            ContentHash(row.value2()),
                            SnapshotReason.valueOf(row.value3()),
                            row.value4().toInstant(),
                            row.value5()?.toInstant(),
                            row.value6(),
                        )
                    }
            ApplicationStoreResult.Success(summaries)
        }

    override fun findById(
        application: ApplicationId,
        id: SnapshotId,
    ): ApplicationStoreResult<DescriptionSnapshot> =
        storeCall("find snapshot") {
            val found =
                dsl
                    .select(APPLICATION_DESCRIPTION_SNAPSHOT.asterisk())
                    .from(APPLICATION_DESCRIPTION_SNAPSHOT)
                    .join(APPLICATION_SOURCE)
                    .on(APPLICATION_SOURCE.ID.eq(APPLICATION_DESCRIPTION_SNAPSHOT.SOURCE_ID))
                    .where(APPLICATION_DESCRIPTION_SNAPSHOT.ID.eq(id.value))
                    .and(APPLICATION_SOURCE.APPLICATION_ID.eq(application.value))
                    .fetchOneInto(APPLICATION_DESCRIPTION_SNAPSHOT)
            found?.let { ApplicationStoreResult.Success(toDomain(it)) } ?: ApplicationStoreResult.NotFound
        }

    /**
     * One statement, as `DescriptionSnapshot.toFreeze` decides per source: of each source of [application]
     * without a frozen snapshot, the newest snapshot captured by [asOf] (ties by id, so exactly one per source).
     */
    override fun freeze(
        application: ApplicationId,
        asOf: Instant,
    ): ApplicationStoreResult<List<SnapshotId>> =
        storeCall("freeze") {
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

    private fun toRecord(snapshot: DescriptionSnapshot): ApplicationDescriptionSnapshotRecord =
        ApplicationDescriptionSnapshotRecord().apply {
            id = snapshot.id.value
            sourceId = snapshot.source.value
            description = snapshot.text.value
            contentHash = snapshot.contentHash.hex
            reason = snapshot.reason.name
            capturedAt = snapshot.capturedAt.atOffset(ZoneOffset.UTC)
            frozenAt = snapshot.frozenAt?.atOffset(ZoneOffset.UTC)
        }

    private fun toDomain(record: ApplicationDescriptionSnapshotRecord): DescriptionSnapshot =
        DescriptionSnapshot(
            SnapshotId(record.id),
            SourceId(record.sourceId),
            DescriptionText(record.description),
            SnapshotReason.valueOf(record.reason),
            record.capturedAt.toInstant(),
            record.frozenAt?.toInstant(),
        )

    /**
     * No exception crosses the port. An insert whose source is gone is recognised by the foreign key's name;
     * messages can carry row values (posting text), so only the exception type is logged.
     */
    private fun <T> storeCall(
        operation: String,
        block: () -> ApplicationStoreResult<T>,
    ): ApplicationStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            if (exception.violatedConstraint() == SNAPSHOT_SOURCE_FK) {
                ApplicationStoreResult.NotFound
            } else {
                log.error("Description snapshot store {} failed: {}", operation, exception.javaClass.name)
                ApplicationStoreResult.StorageFailure(operation)
            }
        }

    private companion object {
        /** `application_description_snapshot.source_id`: the source (or its application) is gone. */
        const val SNAPSHOT_SOURCE_FK = "application_description_snapshot_source_fk"

        val log: Logger = LoggerFactory.getLogger(DescriptionSnapshotRepository::class.java)
    }
}
