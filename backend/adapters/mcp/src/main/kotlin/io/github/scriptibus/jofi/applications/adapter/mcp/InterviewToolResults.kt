// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * The interview tools' results. The notes (preparation and afterwards) and an application's title are [Untrusted]:
 * a tool can write the notes, and titles are copied from postings (ADR-0053, amendment of #119). Ids, versions, the
 * time, the type and the outcome stay plain.
 */
data class InterviewNotes(
    val preparationNotes: String?,
    val notes: String?,
)

/** One interview in full; [version] is what `update_interview` needs to be based on. */
data class InterviewResult(
    val id: UUID,
    val applicationId: UUID,
    val version: Long,
    val type: InterviewType,
    val startsAt: Instant,
    val localStart: LocalDateTime,
    val timeZone: String,
    val participantIds: List<UUID>,
    val outcome: InterviewOutcome?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val interview: Untrusted<InterviewNotes>,
) {
    companion object {
        fun from(interview: Interview): InterviewResult {
            val details = interview.details
            return InterviewResult(
                interview.id.value,
                interview.application.value,
                interview.version,
                details.type,
                details.time.startsAt,
                details.time.localStart,
                details.time.zone.id,
                details.participants.map { it.value }.sorted(),
                details.outcome,
                interview.createdAt,
                interview.updatedAt,
                Untrusted(InterviewNotes(details.preparationNotes, details.notes)),
            )
        }
    }
}

/** The interviews of one application in the order they start; [total] is their number, [interviews] at most 50. */
data class InterviewListResult(
    val total: Int,
    val interviews: List<InterviewResult>,
) {
    companion object {
        /** A client's context is not for an unbounded list; an application has a handful of interviews. */
        const val MAX_LISTED = 50

        fun from(interviews: List<Interview>) =
            InterviewListResult(interviews.size, interviews.take(MAX_LISTED).map(InterviewResult::from))
    }
}

data class ApplicationTitle(
    val title: String,
)

/** An interview still to come, without its notes (`list_interviews` has them), with its application's title. */
data class UpcomingInterviewResult(
    val id: UUID,
    val applicationId: UUID,
    val type: InterviewType,
    val startsAt: Instant,
    val localStart: LocalDateTime,
    val timeZone: String,
    val outcome: InterviewOutcome?,
    val application: Untrusted<ApplicationTitle>,
) {
    companion object {
        fun from(upcoming: UpcomingInterview): UpcomingInterviewResult {
            val interview = upcoming.interview
            val time = interview.details.time
            return UpcomingInterviewResult(
                interview.id.value,
                interview.application.value,
                interview.details.type,
                time.startsAt,
                time.localStart,
                time.zone.id,
                interview.details.outcome,
                Untrusted(ApplicationTitle(upcoming.applicationTitle)),
            )
        }
    }
}

/** The interviews to come across all applications, soonest first. */
data class UpcomingInterviewsResult(
    val interviews: List<UpcomingInterviewResult>,
) {
    companion object {
        fun from(upcoming: List<UpcomingInterview>) =
            UpcomingInterviewsResult(upcoming.map(UpcomingInterviewResult::from))
    }
}
