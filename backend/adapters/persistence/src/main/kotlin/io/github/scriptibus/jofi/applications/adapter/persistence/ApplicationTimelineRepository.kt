// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.ApplicationTimelineRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.TimelineEntry
import io.github.scriptibus.jofi.applications.domain.TimelineEntryKind
import io.github.scriptibus.jofi.applications.domain.TimelinePosition
import io.github.scriptibus.jofi.applications.domain.TimelineQuery
import io.github.scriptibus.jofi.shared.adapter.persistence.ActorColumns
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_DESCRIPTION_SNAPSHOT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SOURCE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The timeline sources of the applications context (#87): one keyset query per source, newest first, each served by
 * its index on the application (`changelog_entry_entity_idx`, `application_status_change_application_idx`,
 * `application_source_application_idx`, `interview_application_idx`). Only the columns an entry shows are read: a
 * change's values only for `TimelineEntry.Change.VALUED_FIELDS` (never free text), no reasons, notes or description
 * texts.
 */
@Component
class ApplicationTimelineRepository(
    private val dsl: DSLContext,
) : ApplicationTimelineRepositoryPort {
    override fun entries(
        id: ApplicationId,
        query: TimelineQuery,
    ): ApplicationStoreResult<List<TimelineEntry>> =
        try {
            if (dsl.fetchExists(APPLICATION, APPLICATION.ID.eq(id.value))) {
                ApplicationStoreResult.Success(
                    changes(id, query) + statusChanges(id, query) + snapshots(id, query) + interviews(id, query),
                )
            } else {
                ApplicationStoreResult.NotFound
            }
        } catch (exception: RuntimeException) {
            log.error("Reading the application timeline failed: {}", exception.javaClass.name)
            ApplicationStoreResult.StorageFailure("timeline")
        }

    /** The application's changelog entries but those of a status change, which the history shows with its reason. */
    private fun changes(
        id: ApplicationId,
        query: TimelineQuery,
    ): List<TimelineEntry> {
        val entry = CHANGELOG_ENTRY
        return dsl
            .select(entry.ID, entry.OCCURRED_AT, entry.ACTOR_KIND, entry.ACTOR_NAME)
            .select(FIELD_NAMES, VALUES_BEFORE, VALUES_AFTER)
            .from(entry)
            .where(entry.ENTITY_TYPE.eq(ApplicationId.ENTITY_TYPE))
            .and(entry.ENTITY_ID.eq(id.value.toString()))
            .and(NOT_ONLY_STATUS)
            .and(after(query.before, TimelineEntryKind.CHANGE, entry.OCCURRED_AT, entry.ID) { it.number() })
            .orderBy(entry.OCCURRED_AT.desc(), entry.ID.desc())
            .limit(query.fetchSize)
            .fetch { row ->
                TimelineEntry.Change(
                    row[entry.ID],
                    row[entry.OCCURRED_AT].toInstant(),
                    ActorColumns.toActor(row[entry.ACTOR_KIND], row[entry.ACTOR_NAME]),
                    row[FIELD_NAMES].mapIndexed { index, field ->
                        TimelineEntry.ChangedField(field, row[VALUES_BEFORE][index], row[VALUES_AFTER][index])
                    },
                )
            }
    }

    private fun statusChanges(
        id: ApplicationId,
        query: TimelineQuery,
    ): List<TimelineEntry> {
        val change = APPLICATION_STATUS_CHANGE
        return dsl
            .select(change.ID, change.CHANGED_AT, change.ACTOR_KIND, change.ACTOR_NAME)
            .select(change.FROM_STATUS, change.TO_STATUS, change.DECLINE_CATEGORY)
            .from(change)
            .where(change.APPLICATION_ID.eq(id.value))
            .and(after(query.before, TimelineEntryKind.STATUS_CHANGE, change.CHANGED_AT, change.ID) { it.number() })
            .orderBy(change.CHANGED_AT.desc(), change.ID.desc())
            .limit(query.fetchSize)
            .fetch { row ->
                TimelineEntry.StatusChanged(
                    row[change.ID],
                    row[change.CHANGED_AT].toInstant(),
                    ActorColumns.toActor(row[change.ACTOR_KIND], row[change.ACTOR_NAME]),
                    row[change.FROM_STATUS]?.let(ApplicationStatus::valueOf),
                    ApplicationStatus.valueOf(row[change.TO_STATUS]),
                    row[change.DECLINE_CATEGORY]?.let(DeclineCategory::valueOf),
                )
            }
    }

    private fun snapshots(
        id: ApplicationId,
        query: TimelineQuery,
    ): List<TimelineEntry> {
        val snapshot = APPLICATION_DESCRIPTION_SNAPSHOT
        return dsl
            .select(snapshot.ID, snapshot.CAPTURED_AT, snapshot.SOURCE_ID, snapshot.REASON, snapshot.FROZEN_AT)
            .from(snapshot)
            .join(APPLICATION_SOURCE)
            .on(APPLICATION_SOURCE.ID.eq(snapshot.SOURCE_ID))
            .where(APPLICATION_SOURCE.APPLICATION_ID.eq(id.value))
            .and(
                after(
                    query.before,
                    TimelineEntryKind.DESCRIPTION_SNAPSHOT,
                    snapshot.CAPTURED_AT,
                    snapshot.ID,
                ) { it.uuid() },
            ).orderBy(snapshot.CAPTURED_AT.desc(), snapshot.ID.desc())
            .limit(query.fetchSize)
            .fetch { row ->
                TimelineEntry.DescriptionCaptured(
                    SnapshotId(row[snapshot.ID]),
                    row[snapshot.CAPTURED_AT].toInstant(),
                    SourceId(row[snapshot.SOURCE_ID]),
                    SnapshotReason.valueOf(row[snapshot.REASON]),
                    row[snapshot.FROZEN_AT]?.toInstant(),
                )
            }
    }

    private fun interviews(
        id: ApplicationId,
        query: TimelineQuery,
    ): List<TimelineEntry> {
        val interview = INTERVIEW
        return dsl
            .select(interview.ID, interview.STARTS_AT, interview.KIND, interview.TIME_ZONE, interview.OUTCOME)
            .from(interview)
            .where(interview.APPLICATION_ID.eq(id.value))
            .and(after(query.before, TimelineEntryKind.INTERVIEW, interview.STARTS_AT, interview.ID) { it.uuid() })
            .orderBy(interview.STARTS_AT.desc(), interview.ID.desc())
            .limit(query.fetchSize)
            .fetch { row ->
                TimelineEntry.InterviewPlanned(
                    InterviewId(row[interview.ID]),
                    row[interview.STARTS_AT].toInstant(),
                    InterviewType.valueOf(row[interview.KIND]),
                    ZoneId.of(row[interview.TIME_ZONE]),
                    row[interview.OUTCOME]?.let(InterviewOutcome::valueOf),
                )
            }
    }

    internal companion object {
        private val log: Logger = LoggerFactory.getLogger(ApplicationTimelineRepository::class.java)

        /** The names of the changed fields, in the order of the entry (also read by [DashboardRepository]). */
        val FIELD_NAMES: Field<Array<String>> = fieldChanges("change ->> 'field'")

        /**
         * The values before, by position: only those of `TimelineEntry.Change.VALUED_FIELDS` leave the database,
         * the others (free text) are null.
         */
        private val VALUES_BEFORE: Field<Array<String>> = fieldChanges(valued("before"))

        /** The values after, as [VALUES_BEFORE]. */
        private val VALUES_AFTER: Field<Array<String>> = fieldChanges(valued("after"))

        private fun valued(value: String): String =
            "case when change ->> 'field' in (" +
                TimelineEntry.Change.VALUED_FIELDS.joinToString { "'$it'" } +
                ") then change ->> '$value' end"

        /** [expression] of every field change (`change`) of the entry, in the entry's order. */
        private fun fieldChanges(expression: String): Field<Array<String>> =
            DSL.field(
                "array(select $expression from jsonb_array_elements({0}) with ordinality as element(change, ordinal) " +
                    "order by ordinal)",
                SQLDataType.VARCHAR.array(),
                CHANGELOG_ENTRY.FIELD_CHANGES,
            )

        /** A status change writes `status` and `declineReason` only (ADR-0044); every other entry stays. */
        private val NOT_ONLY_STATUS: Condition =
            DSL.condition(
                "jsonb_array_length({0}) = 0 or exists (select 1 from jsonb_array_elements({0}) change " +
                    "where change ->> 'field' not in ({1}, {2}))",
                CHANGELOG_ENTRY.FIELD_CHANGES,
                DSL.inline("status"),
                DSL.inline("declineReason"),
            )

        /**
         * The rows of [kind] after [before] in the timeline's order (newest first, then by kind, then by id): of
         * the same kind, older or of the same instant with a lower id; of a kind shown later in an instant, the
         * same instant too; of one shown earlier, only older ones.
         */
        private fun <T : Any> after(
            before: TimelinePosition?,
            kind: TimelineEntryKind,
            occurredAt: Field<OffsetDateTime>,
            id: Field<T>,
            idOf: (TimelinePosition) -> T,
        ): Condition {
            if (before == null) return DSL.noCondition()
            val at = before.occurredAt.atOffset(ZoneOffset.UTC)
            return when {
                before.kind == kind -> DSL.row(occurredAt, id).lt(at, idOf(before))
                before.kind > kind -> occurredAt.le(at)
                else -> occurredAt.lt(at)
            }
        }
    }
}
