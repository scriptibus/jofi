// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewInput
import io.github.scriptibus.jofi.applications.domain.InterviewSummary
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.adapter.web.PageResponse
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

// Interview notes are the user's words about other people: DTOs that hold them print none of it, nor who took part.

/**
 * What the user records about an interview or call. [localStart] is the agreed wall-clock time (`2026-10-05T10:00`)
 * in [timeZone], an IANA id such as `Europe/Berlin` or an offset such as `+02:00` (ADR-0048). Text is trimmed and
 * blank text counts as absent; a violation answers 400 naming the request field (`localStart`, `timeZone`,
 * `participantIds`, `preparationNotes`, `notes`).
 */
data class InterviewRequest(
    val type: InterviewKind,
    val localStart: LocalDateTime,
    val timeZone: String,
    /** Contacts who took part, at most 20; one that does not exist is a 400 (`participantIds`, `NOT_FOUND`). */
    val participantIds: List<UUID> = emptyList(),
    /** Markdown. */
    val preparationNotes: String? = null,
    /** Markdown: the user's notes afterwards. */
    val notes: String? = null,
    val outcome: InterviewResultKind? = null,
) {
    fun toInput(): InterviewInput =
        InterviewInput(
            type = type.mapByName(),
            localStart = localStart,
            timeZone = timeZone,
            participants = participantIds.map(::ContactRef).toSet(),
            preparationNotes = preparationNotes,
            notes = notes,
            outcome = outcome?.mapByName(),
        )

    override fun toString(): String = "InterviewRequest(type=$type, localStart=$localStart, timeZone=$timeZone)"
}

/** Body of `PUT /api/applications/{id}/interviews/{interviewId}`: all details and the version they are based on. */
data class UpdateInterviewRequest(
    val details: InterviewRequest,
    val basedOnVersion: Long,
)

/**
 * One interview or call. [startsAt] is the instant it starts (order and count down by it), [localStart] the same
 * moment on the clocks of [timeZone], the zone it was planned in (show it that way). [version] goes back as
 * `basedOnVersion` with the next change; render the notes sanitised.
 */
data class InterviewResponse(
    val id: UUID,
    val applicationId: UUID,
    val type: InterviewKind,
    val startsAt: Instant,
    val localStart: LocalDateTime,
    val timeZone: String,
    val participantIds: List<UUID>,
    val preparationNotes: String?,
    val notes: String?,
    val outcome: InterviewResultKind?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "InterviewResponse(id=$id, type=$type, startsAt=$startsAt, version=$version)"

    companion object {
        fun from(interview: Interview): InterviewResponse {
            val details = interview.details
            return InterviewResponse(
                interview.id.value,
                interview.application.value,
                details.type.mapByName(),
                details.time.startsAt,
                details.time.localStart,
                details.time.zone.id,
                details.participants.map { it.value }.sorted(),
                details.preparationNotes,
                details.notes,
                details.outcome?.mapByName(),
                interview.version,
                interview.createdAt,
                interview.updatedAt,
            )
        }
    }
}

/** Copy of `SortDirection`: oldest (`ASCENDING`) or newest (`DESCENDING`) interview first. */
enum class InterviewListDirection { ASCENDING, DESCENDING }

/**
 * One interview or call as a list shows it (ADR-0056): everything of [InterviewResponse] but the notes, of which the
 * start is given as [preparationNotesExcerpt] and [notesExcerpt] (Markdown, cut at a code point, `null` without
 * notes), each with a flag whether text was left out. `GET /api/applications/{id}/interviews/{interviewId}` has the
 * whole text; render the excerpts sanitised.
 */
data class InterviewSummaryResponse(
    val id: UUID,
    val applicationId: UUID,
    val type: InterviewKind,
    val startsAt: Instant,
    val localStart: LocalDateTime,
    val timeZone: String,
    val participantIds: List<UUID>,
    val preparationNotesExcerpt: String?,
    val preparationNotesTruncated: Boolean,
    val notesExcerpt: String?,
    val notesTruncated: Boolean,
    val outcome: InterviewResultKind?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String =
        "InterviewSummaryResponse(id=$id, type=$type, startsAt=$startsAt, version=$version)"

    companion object {
        fun from(interview: InterviewSummary): InterviewSummaryResponse =
            InterviewSummaryResponse(
                interview.id.value,
                interview.application.value,
                interview.type.mapByName(),
                interview.time.startsAt,
                interview.time.localStart,
                interview.time.zone.id,
                interview.participants.map { it.value }.sorted(),
                interview.preparationNotesExcerpt?.text,
                interview.preparationNotesExcerpt?.truncated ?: false,
                interview.notesExcerpt?.text,
                interview.notesExcerpt?.truncated ?: false,
                interview.outcome?.mapByName(),
                interview.version,
                interview.createdAt,
                interview.updatedAt,
            )
    }
}

/**
 * JSON body of `GET /api/applications/{id}/interviews`: one page of the interviews in the order they start (or
 * newest first), as summaries.
 */
data class InterviewListResponse(
    val interviews: List<InterviewSummaryResponse>,
    val page: PageResponse,
) {
    companion object {
        fun from(page: Paged<InterviewSummary>): InterviewListResponse =
            InterviewListResponse(page.items.map(InterviewSummaryResponse::from), PageResponse.from(page.info))
    }
}

/** An interview still to come with the title of its application. */
data class UpcomingInterviewResponse(
    val interview: InterviewResponse,
    val applicationTitle: String,
) {
    override fun toString(): String = "UpcomingInterviewResponse(interview=$interview)"

    companion object {
        fun from(upcoming: UpcomingInterview): UpcomingInterviewResponse =
            UpcomingInterviewResponse(InterviewResponse.from(upcoming.interview), upcoming.applicationTitle)
    }
}

/** JSON body of `GET /api/interviews/upcoming`: soonest first. */
data class UpcomingInterviewListResponse(
    val interviews: List<UpcomingInterviewResponse>,
) {
    companion object {
        fun from(upcoming: List<UpcomingInterview>): UpcomingInterviewListResponse =
            UpcomingInterviewListResponse(upcoming.map(UpcomingInterviewResponse::from))
    }
}
