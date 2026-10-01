// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW_PARTICIPANT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.InterviewRecord
import org.jooq.DSLContext
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Maps interviews to `interview` rows and back, explicitly and without business logic. */
internal object InterviewRecords {
    fun toRecord(interview: Interview): InterviewRecord =
        detailsRecord(interview).apply {
            id = interview.id.value
            applicationId = interview.application.value
            createdAt = interview.createdAt.atOffset(ZoneOffset.UTC)
        }

    /** The detail columns, `version` and `updated_at`: what `update` writes. */
    fun detailsRecord(interview: Interview): InterviewRecord =
        InterviewRecord().apply {
            val details = interview.details
            kind = details.type.name
            startsAt = details.time.startsAt.atOffset(ZoneOffset.UTC)
            timeZone = details.time.zone.id
            preparationNotes = details.preparationNotes
            notes = details.notes
            outcome = details.outcome?.name
            version = interview.version
            updatedAt = interview.updatedAt.atOffset(ZoneOffset.UTC)
        }

    fun toDomain(
        record: InterviewRecord,
        participants: Set<ContactRef>,
    ): Interview =
        Interview(
            id = InterviewId(record.id),
            application = ApplicationId(record.applicationId),
            details =
                InterviewDetails(
                    type = InterviewType.valueOf(record.kind),
                    time = InterviewTime(record.startsAt.toInstant(), ZoneId.of(record.timeZone)),
                    participants = participants,
                    preparationNotes = record.preparationNotes,
                    notes = record.notes,
                    outcome = record.outcome?.let(InterviewOutcome::valueOf),
                ),
            version = record.version,
            createdAt = record.createdAt.toInstant(),
            updatedAt = record.updatedAt.toInstant(),
        )
}

/** The statements on `interview_participant` that `InterviewRepository` combines; they throw, the repository maps. */
internal class InterviewParticipants(
    private val dsl: DSLContext,
) {
    /** The interviews of [records], in their order, each with its participants (one query for all). */
    fun interviewsOf(records: List<InterviewRecord>): List<Interview> {
        val participants = participantsOf(records.map { it.id })
        return records.map { InterviewRecords.toDomain(it, participants[it.id].orEmpty()) }
    }

    private fun participantsOf(interviews: Collection<UUID>): Map<UUID, Set<ContactRef>> =
        dsl
            .select(INTERVIEW_PARTICIPANT.INTERVIEW_ID, INTERVIEW_PARTICIPANT.CONTACT_ID)
            .from(INTERVIEW_PARTICIPANT)
            .where(INTERVIEW_PARTICIPANT.INTERVIEW_ID.`in`(interviews))
            .fetchGroups(INTERVIEW_PARTICIPANT.INTERVIEW_ID, INTERVIEW_PARTICIPANT.CONTACT_ID)
            .mapValues { (_, contacts) -> contacts.mapTo(mutableSetOf(), ::ContactRef) }

    fun insert(
        interview: InterviewId,
        participants: Set<ContactRef>,
    ) {
        if (participants.isEmpty()) return
        val insert =
            dsl.insertInto(INTERVIEW_PARTICIPANT, INTERVIEW_PARTICIPANT.INTERVIEW_ID, INTERVIEW_PARTICIPANT.CONTACT_ID)
        participants.fold(insert) { statement, contact -> statement.values(interview.value, contact.value) }.execute()
    }

    /** Rewrites the participants of [interview] to exactly [participants], only if the stored set differs. */
    fun replaceIfChanged(
        interview: InterviewId,
        participants: Set<ContactRef>,
    ) {
        if (participantsOf(listOf(interview.value))[interview.value].orEmpty() == participants) return
        dsl.deleteFrom(INTERVIEW_PARTICIPANT).where(INTERVIEW_PARTICIPANT.INTERVIEW_ID.eq(interview.value)).execute()
        insert(interview, participants)
    }
}
