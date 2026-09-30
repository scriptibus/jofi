// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.ApplicationActivityRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * When the open applications last had activity (#85): the latest of their last status change, their interviews' last
 * change (`updated_at`, set on creation too) and start, and the last changelog entry that changed their contact links
 * (field `contacts`, written by the link use case and the contact delete). PostgreSQL's `GREATEST` skips the parts an
 * application does not have; every application has a status history, so the result is never null. Only ids, titles
 * and times are read; only the exception type is logged.
 */
@Component
class ApplicationActivityRepository(
    private val dsl: DSLContext,
) : ApplicationActivityRepositoryPort {
    override fun silentSince(cutoff: Instant): ApplicationStoreResult<List<FindGhostedCandidatesPort.Candidate>> =
        try {
            val lastActivity = lastActivity()
            val candidates =
                dsl
                    .select(APPLICATION.ID, APPLICATION.TITLE, lastActivity)
                    .from(APPLICATION)
                    .where(APPLICATION.STATUS.`in`(OPEN_STATUSES))
                    .and(lastActivity.le(cutoff.atOffset(ZoneOffset.UTC)))
                    .orderBy(lastActivity, APPLICATION.ID)
                    .fetch { (id, title, since) -> FindGhostedCandidatesPort.Candidate(id, title, since.toInstant()) }
            ApplicationStoreResult.Success(candidates)
        } catch (exception: RuntimeException) {
            log.error("Reading application activity failed: {}", exception.javaClass.name)
            ApplicationStoreResult.StorageFailure("silentSince")
        }

    private fun lastActivity(): Field<OffsetDateTime> {
        val lastStatusChange =
            DSL.field(
                DSL
                    .select(DSL.max(APPLICATION_STATUS_CHANGE.CHANGED_AT))
                    .from(APPLICATION_STATUS_CHANGE)
                    .where(APPLICATION_STATUS_CHANGE.APPLICATION_ID.eq(APPLICATION.ID)),
            )
        val lastInterview =
            DSL.field(
                DSL
                    .select(DSL.max(DSL.greatest(INTERVIEW.UPDATED_AT, INTERVIEW.STARTS_AT)))
                    .from(INTERVIEW)
                    .where(INTERVIEW.APPLICATION_ID.eq(APPLICATION.ID)),
            )
        val lastContactChange =
            DSL.field(
                DSL
                    .select(DSL.max(CHANGELOG_ENTRY.OCCURRED_AT))
                    .from(CHANGELOG_ENTRY)
                    .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq(ApplicationId.ENTITY_TYPE))
                    .and(CHANGELOG_ENTRY.ENTITY_ID.eq(APPLICATION.ID.cast(SQLDataType.VARCHAR)))
                    .and(DSL.condition("{0} @> {1}", CHANGELOG_ENTRY.FIELD_CHANGES, DSL.value(CONTACTS_CHANGE))),
            )
        return DSL.greatest(lastStatusChange, lastInterview, lastContactChange)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ApplicationActivityRepository::class.java)

        /** The statuses a Ghosted suggestion is for (spec §6.2): waiting for an answer after applying. */
        val OPEN_STATUSES = listOf(ApplicationStatus.APPLIED.name, ApplicationStatus.INTERVIEWING.name)

        /** Matches a `field_changes` array with a change of the field `contacts`, whatever its values. */
        val CONTACTS_CHANGE: JSONB = JSONB.jsonb("""[{"field":"contacts"}]""")
    }
}
