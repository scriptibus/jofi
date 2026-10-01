// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.DashboardRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ActivityApplication
import io.github.scriptibus.jofi.applications.domain.ActivityEntry
import io.github.scriptibus.jofi.applications.domain.ActivityQuery
import io.github.scriptibus.jofi.applications.domain.ApplicationFunnel
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.FunnelStage
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.PipelineOverview
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.adapter.persistence.ActorColumns
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_DESCRIPTION_SNAPSHOT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SOURCE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.github.scriptibus.jofi.shared.domain.EntityRef
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Record3
import org.jooq.Select
import org.jooq.impl.DSL
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The dashboard's figures of the applications context (ADR-0052), each a single aggregate or keyset query: the status
 * counts and the unread count over `application`, the funnel over `application_status_change`, the recent activity
 * over `changelog_entry_recent_idx`, labelled with the job titles of the applications the entries are about.
 */
@Component
class DashboardRepository(
    private val dsl: DSLContext,
) : DashboardRepositoryPort {
    /** Counts and unread in one query, so the unread ones are always among the counted (a delete in between). */
    override fun pipeline(): ApplicationStoreResult<PipelineOverview> =
        guarded("pipeline") {
            val count = DSL.count()
            val unread = DSL.count().filterWhere(APPLICATION.UNREAD.isTrue)
            val rows =
                dsl
                    .select(APPLICATION.STATUS, count, unread)
                    .from(APPLICATION)
                    .groupBy(APPLICATION.STATUS)
                    .fetch()
            PipelineOverview.of(
                rows.associate { ApplicationStatus.valueOf(it[APPLICATION.STATUS]) to it[count].toLong() },
                rows.sumOf { it[unread].toLong() },
                funnel(),
            )
        }

    /** Each count is of the applications whose history holds one of the stage's statuses, whatever their status now. */
    private fun funnel(): ApplicationFunnel {
        val row =
            dsl
                .select(
                    reached(FunnelStage.APPLIED.statuses),
                    reached(FunnelStage.INTERVIEW.statuses),
                    reached(FunnelStage.OFFER.statuses),
                    reached(FunnelStage.RESPONSE),
                ).from(APPLICATION_STATUS_CHANGE)
                .fetchSingle()
        return ApplicationFunnel(
            row.value1().toLong(),
            row.value2().toLong(),
            row.value3().toLong(),
            row.value4().toLong(),
        )
    }

    private fun reached(statuses: Set<ApplicationStatus>): Field<Int> =
        DSL
            .countDistinct(APPLICATION_STATUS_CHANGE.APPLICATION_ID)
            .filterWhere(APPLICATION_STATUS_CHANGE.TO_STATUS.`in`(statuses.map { it.name }))

    override fun recentActivity(query: ActivityQuery): ApplicationStoreResult<List<ActivityEntry>> =
        guarded("recentActivity") {
            val entry = CHANGELOG_ENTRY
            val rows =
                dsl
                    .select(entry.ID, entry.OCCURRED_AT, entry.ACTOR_KIND, entry.ACTOR_NAME, entry.ENTITY_TYPE)
                    .select(entry.ENTITY_ID, entry.DESCRIPTION, ApplicationTimelineRepository.FIELD_NAMES)
                    .from(entry)
                    .where(entry.ENTITY_TYPE.`in`(ActivityQuery.ENTITY_TYPES))
                    .orderBy(entry.OCCURRED_AT.desc(), entry.ID.desc())
                    .limit(query.limit)
                    .fetch()
            val applications = applicationsOf(rows.map { EntityRef(it[entry.ENTITY_TYPE], it[entry.ENTITY_ID]) })
            rows.map { row -> activityEntry(row, applications) }
        }

    private fun activityEntry(
        row: Record,
        applications: Map<EntityRef, ActivityApplication>,
    ): ActivityEntry {
        val entry = CHANGELOG_ENTRY
        val entity = EntityRef(row[entry.ENTITY_TYPE], row[entry.ENTITY_ID])
        return ActivityEntry(
            id = row[entry.ID],
            occurredAt = row[entry.OCCURRED_AT].toInstant(),
            actor = ActorColumns.toActor(row[entry.ACTOR_KIND], row[entry.ACTOR_NAME]),
            entity = entity,
            description = row[entry.DESCRIPTION],
            fields = row[ApplicationTimelineRepository.FIELD_NAMES].toList(),
            application = applications[entity],
        )
    }

    /**
     * The existing application each entity of the applications context is about, with its title, in one query: an
     * application for itself, an interview, source or description snapshot for its application. Other entities, gone
     * ones and ids that are no UUID are left out.
     */
    private fun applicationsOf(entities: List<EntityRef>): Map<EntityRef, ActivityApplication> {
        val ids =
            entities
                .mapNotNull { entity ->
                    entity.toUuid()?.let { entity.type to it }
                }.groupBy({ it.first }, { it.second })
        if (ids.keys.none { it in OWNED_TYPES }) return emptyMap()
        val owned = owners(ids).asTable("owned", "entity_type", "entity_id", "application_id")
        val type = DSL.field(DSL.name("owned", "entity_type"), String::class.java)
        val id = DSL.field(DSL.name("owned", "entity_id"), UUID::class.java)
        val application = DSL.field(DSL.name("owned", "application_id"), UUID::class.java)
        return dsl
            .select(type, id, APPLICATION.ID, APPLICATION.TITLE)
            .from(owned)
            .join(APPLICATION)
            .on(APPLICATION.ID.eq(application))
            .fetch()
            .associate { row ->
                EntityRef(row[type], row[id].toString()) to
                    ActivityApplication(ApplicationId(row[APPLICATION.ID]), row[APPLICATION.TITLE])
            }
    }

    /** (entity type, entity id, application id) of the entities of [ids] (by type) that belong to an application. */
    private fun owners(ids: Map<String, List<UUID>>): Select<Record3<String, UUID, UUID>> {
        fun of(type: String) = ids[type].orEmpty()
        val source = APPLICATION_SOURCE
        val snapshot = APPLICATION_DESCRIPTION_SNAPSHOT
        return DSL
            .select(DSL.inline(ApplicationId.ENTITY_TYPE), APPLICATION.ID, APPLICATION.ID)
            .from(APPLICATION)
            .where(APPLICATION.ID.`in`(of(ApplicationId.ENTITY_TYPE)))
            .unionAll(
                DSL
                    .select(DSL.inline(InterviewId.ENTITY_TYPE), INTERVIEW.ID, INTERVIEW.APPLICATION_ID)
                    .from(INTERVIEW)
                    .where(INTERVIEW.ID.`in`(of(InterviewId.ENTITY_TYPE))),
            ).unionAll(
                DSL
                    .select(DSL.inline(SourceId.ENTITY_TYPE), source.ID, source.APPLICATION_ID)
                    .from(source)
                    .where(source.ID.`in`(of(SourceId.ENTITY_TYPE))),
            ).unionAll(
                DSL
                    .select(DSL.inline(SnapshotId.ENTITY_TYPE), snapshot.ID, source.APPLICATION_ID)
                    .from(snapshot)
                    .join(source)
                    .on(source.ID.eq(snapshot.SOURCE_ID))
                    .where(snapshot.ID.`in`(of(SnapshotId.ENTITY_TYPE))),
            )
    }

    private fun EntityRef.toUuid(): UUID? =
        runCatching { UUID.fromString(id) }.getOrNull()?.takeIf {
            it.toString() ==
                id
        }

    private fun <T> guarded(
        operation: String,
        read: () -> T,
    ): ApplicationStoreResult<T> =
        try {
            ApplicationStoreResult.Success(read())
        } catch (exception: RuntimeException) {
            log.error("Reading the dashboard failed ({}): {}", operation, exception.javaClass.name)
            ApplicationStoreResult.StorageFailure(operation)
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(DashboardRepository::class.java)

        /** The entity types of the applications context whose entries name an application. */
        val OWNED_TYPES =
            setOf(ApplicationId.ENTITY_TYPE, InterviewId.ENTITY_TYPE, SourceId.ENTITY_TYPE, SnapshotId.ENTITY_TYPE)
    }
}
