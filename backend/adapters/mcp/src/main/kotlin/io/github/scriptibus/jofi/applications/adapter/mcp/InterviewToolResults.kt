// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewSummary
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.shared.domain.paging.Paged
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

/**
 * One interview in full; [version] is what `update_interview` needs to be based on. Everything above [readOnly] goes
 * back to `update_interview` under the same keys (the content of `interview` under its key); [readOnly] does not.
 */
data class InterviewResult(
    val id: UUID,
    val applicationId: UUID,
    val version: Long,
    val type: InterviewType,
    val localStart: LocalDateTime,
    val timeZone: String,
    val participantIds: List<UUID>,
    val outcome: InterviewOutcome?,
    val interview: Untrusted<InterviewNotes>,
    val readOnly: InterviewReadOnly,
) {
    companion object {
        fun from(interview: Interview): InterviewResult {
            val details = interview.details
            return InterviewResult(
                interview.id.value,
                interview.application.value,
                interview.version,
                details.type,
                details.time.localStart,
                details.time.zone.id,
                details.participants.map { it.value }.sorted(),
                details.outcome,
                Untrusted(InterviewNotes(details.preparationNotes, details.notes)),
                InterviewReadOnly(details.time.startsAt, interview.createdAt, interview.updatedAt),
            )
        }
    }
}

/** What no tool sets: the instant the interview starts (derived from the local time and the zone) and the stamps. */
data class InterviewReadOnly(
    val startsAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The start of both notes, as a list shows them (ADR-0056). The keys differ from [InterviewNotes] on purpose: an
 * excerpt is never the whole note, so `update_interview` refuses a list entry (it lacks `preparationNotes` and `notes`
 * and has keys the schema does not know) instead of storing an excerpt over the real text. `get_interview` has the
 * whole notes.
 */
data class InterviewExcerpts(
    val preparationNotesExcerpt: String?,
    val preparationNotesTruncated: Boolean,
    val notesExcerpt: String?,
    val notesTruncated: Boolean,
)

/**
 * One interview of a list: [InterviewResult] with excerpts of the notes in place of the notes, and **no `version`**:
 * `update_interview` needs it, and it comes from `get_interview`, so an update cannot be put together from a list
 * entry alone.
 */
data class InterviewSummaryResult(
    val id: UUID,
    val applicationId: UUID,
    val type: InterviewType,
    val localStart: LocalDateTime,
    val timeZone: String,
    val participantIds: List<UUID>,
    val outcome: InterviewOutcome?,
    val interview: Untrusted<InterviewExcerpts>,
    val readOnly: InterviewReadOnly,
) {
    companion object {
        fun from(interview: InterviewSummary) =
            InterviewSummaryResult(
                interview.id.value,
                interview.application.value,
                interview.type,
                interview.time.localStart,
                interview.time.zone.id,
                interview.participants.map { it.value }.sorted(),
                interview.outcome,
                Untrusted(
                    InterviewExcerpts(
                        interview.preparationNotesExcerpt?.text,
                        interview.preparationNotesExcerpt?.truncated ?: false,
                        interview.notesExcerpt?.text,
                        interview.notesExcerpt?.truncated ?: false,
                    ),
                ),
                InterviewReadOnly(interview.time.startsAt, interview.createdAt, interview.updatedAt),
            )
    }
}

/** One page of the interviews of one application; ask for the next `page` while [hasMore] is true. */
data class InterviewListResult(
    val page: Int,
    val size: Int,
    val total: Int,
    val hasMore: Boolean,
    val interviews: List<InterviewSummaryResult>,
) {
    companion object {
        fun from(page: Paged<InterviewSummary>) =
            InterviewListResult(
                page.info.page,
                page.info.size,
                page.info.total,
                page.info.hasMore,
                page.items.map(InterviewSummaryResult::from),
            )
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
