// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.github.scriptibus.jofi.shared.adapter.persistence.violatedConstraint
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import org.jooq.Condition
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset

/**
 * Interviews and calls (`interview`) and who took part (`interview_participant`), in the caller's transaction
 * (ADR-0048). The `application` row is never written. An update stores only on top of the version it was based on
 * and rewrites the participants only when the set differs. Foreign-key violations are mapped by constraint name;
 * notes describe people, so only operations and exception types are logged.
 */
@Component
class InterviewRepository(
    private val dsl: DSLContext,
) : InterviewRepositoryPort {
    private val participants = InterviewParticipants(dsl)

    override fun add(interview: Interview): ApplicationStoreResult<Unit> =
        storeCall("add interview") {
            dsl.insertInto(INTERVIEW).set(InterviewRecords.toRecord(interview)).execute()
            participants.insert(interview.id, interview.details.participants)
            ApplicationStoreResult.Success(Unit)
        }

    override fun update(interview: Interview): ApplicationStoreResult<Unit> =
        storeCall("update interview") {
            val updated =
                dsl
                    .update(INTERVIEW)
                    .set(InterviewRecords.detailsRecord(interview))
                    .where(of(interview.application, interview.id))
                    .and(INTERVIEW.VERSION.eq(interview.version - 1))
                    .execute()
            when {
                updated == 1 -> {
                    participants.replaceIfChanged(interview.id, interview.details.participants)
                    ApplicationStoreResult.Success(Unit)
                }

                dsl.fetchExists(INTERVIEW, of(interview.application, interview.id)) -> {
                    ApplicationStoreResult.VersionConflict
                }

                else -> {
                    ApplicationStoreResult.NotFound
                }
            }
        }

    override fun findById(
        application: ApplicationId,
        id: InterviewId,
    ): ApplicationStoreResult<Interview> =
        storeCall("find interview") {
            dsl
                .fetchOne(INTERVIEW, of(application, id))
                ?.let { ApplicationStoreResult.Success(participants.interviewsOf(listOf(it)).single()) }
                ?: ApplicationStoreResult.NotFound
        }

    override fun listByApplication(application: ApplicationId): ApplicationStoreResult<List<Interview>> =
        storeCall("list interviews") {
            val records =
                dsl
                    .selectFrom(INTERVIEW)
                    .where(INTERVIEW.APPLICATION_ID.eq(application.value))
                    .orderBy(INTERVIEW.STARTS_AT, INTERVIEW.ID)
                    .fetch()
            ApplicationStoreResult.Success(participants.interviewsOf(records))
        }

    override fun upcoming(
        from: Instant,
        limit: Int,
    ): ApplicationStoreResult<List<UpcomingInterview>> =
        storeCall("upcoming interviews") {
            val rows =
                dsl
                    .select(INTERVIEW.asterisk(), APPLICATION.TITLE)
                    .from(INTERVIEW)
                    .join(APPLICATION)
                    .on(APPLICATION.ID.eq(INTERVIEW.APPLICATION_ID))
                    .where(INTERVIEW.STARTS_AT.ge(from.atOffset(ZoneOffset.UTC)))
                    .and(INTERVIEW.OUTCOME.isDistinctFrom(InterviewOutcome.CANCELLED.name))
                    .orderBy(INTERVIEW.STARTS_AT, INTERVIEW.ID)
                    .limit(limit)
                    .fetch()
            val interviews = participants.interviewsOf(rows.map { it.into(INTERVIEW) })
            ApplicationStoreResult.Success(
                interviews.zip(rows) { interview, row -> UpcomingInterview(interview, row.get(APPLICATION.TITLE)) },
            )
        }

    override fun delete(
        application: ApplicationId,
        id: InterviewId,
        proof: ConfirmationResult.Confirmed,
    ): ApplicationStoreResult<Unit> {
        if (!proof.covers(Interview.DELETE_OPERATION, id.value.toString())) return ApplicationStoreResult.NotConfirmed
        return storeCall("delete interview") {
            val deleted = dsl.deleteFrom(INTERVIEW).where(of(application, id)).execute()
            if (deleted == 0) ApplicationStoreResult.NotFound else ApplicationStoreResult.Success(Unit)
        }
    }

    private fun of(
        application: ApplicationId,
        id: InterviewId,
    ): Condition = INTERVIEW.ID.eq(id.value).and(INTERVIEW.APPLICATION_ID.eq(application.value))

    /**
     * No exception crosses the port. A missing application or contact is recognised by the name of the violated
     * foreign key (ADR-0041); messages can carry row values, so only the exception type is logged.
     */
    private fun <T> storeCall(
        operation: String,
        block: () -> ApplicationStoreResult<T>,
    ): ApplicationStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            when (exception.violatedConstraint()) {
                INTERVIEW_APPLICATION_FK -> {
                    ApplicationStoreResult.NotFound
                }

                INTERVIEW_PARTICIPANT_CONTACT_FK -> {
                    ApplicationStoreResult.ContactNotFound
                }

                else -> {
                    log.error("Interview store {} failed: {}", operation, exception.javaClass.name)
                    ApplicationStoreResult.StorageFailure(operation)
                }
            }
        }

    private companion object {
        /** `interview.application_id`: the application does not exist (any more). */
        const val INTERVIEW_APPLICATION_FK = "interview_application_fk"

        /** `interview_participant.contact_id`: a participant is no contact (any more). */
        const val INTERVIEW_PARTICIPANT_CONTACT_FK = "interview_participant_contact_fk"

        val log: Logger = LoggerFactory.getLogger(InterviewRepository::class.java)
    }
}
