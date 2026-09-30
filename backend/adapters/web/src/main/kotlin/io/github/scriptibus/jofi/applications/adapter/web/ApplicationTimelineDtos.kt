// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.TimelineEntry
import io.github.scriptibus.jofi.applications.domain.TimelinePage
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/** Copy of `TimelineEntryKind`: which of an entry's details is set. */
enum class TimelineKind { CHANGE, STATUS_CHANGE, DESCRIPTION_SNAPSHOT, INTERVIEW, TASK }

/**
 * One timeline entry (#87): [kind] says which one of the detail properties is set (the one of the same name), so a
 * new kind adds a property and never changes an existing one. [id] is unique within its kind (a number for changes
 * and status changes, else the snapshot's, interview's or task's id).
 */
data class TimelineEntryResponse(
    val kind: TimelineKind,
    val id: String,
    val occurredAt: Instant,
    val change: TimelineChangeDto? = null,
    val statusChange: TimelineStatusChangeDto? = null,
    val descriptionSnapshot: TimelineSnapshotDto? = null,
    val interview: TimelineInterviewDto? = null,
    val task: TimelineTaskDto? = null,
) {
    companion object {
        fun from(entry: TimelineEntry): TimelineEntryResponse {
            val position = entry.position
            val base = TimelineEntryResponse(position.kind.mapByName(), position.id, entry.occurredAt)
            return when (entry) {
                is TimelineEntry.Change -> {
                    base.copy(change = TimelineChangeDto(ChangeActorDto.from(entry.actor), entry.fields))
                }

                is TimelineEntry.StatusChanged -> {
                    base.copy(statusChange = TimelineStatusChangeDto.from(entry))
                }

                is TimelineEntry.DescriptionCaptured -> {
                    base.copy(
                        descriptionSnapshot =
                            TimelineSnapshotDto(entry.source.value, entry.reason.mapByName(), entry.frozenAt),
                    )
                }

                is TimelineEntry.InterviewPlanned -> {
                    base.copy(interview = TimelineInterviewDto.from(entry))
                }

                is TimelineEntry.TaskAdded -> {
                    base.copy(task = TimelineTaskDto(entry.title, entry.completedAt))
                }
            }
        }
    }
}

/** [actor] changed the application's [fields] (names only; a field with personal or free-text values is not named). */
data class TimelineChangeDto(
    val actor: ChangeActorDto,
    val fields: List<String>,
)

/** [actor] moved the application [from] one status [to] another; the reason is in the status history. */
data class TimelineStatusChangeDto(
    val actor: ChangeActorDto,
    val from: PipelineStatus?,
    val to: PipelineStatus,
    val declineCategory: DeclineReasonCategory?,
) {
    companion object {
        fun from(entry: TimelineEntry.StatusChanged): TimelineStatusChangeDto =
            TimelineStatusChangeDto(
                ChangeActorDto.from(entry.actor),
                entry.from?.mapByName(),
                entry.to.mapByName(),
                entry.declineCategory?.mapByName(),
            )
    }
}

/** A job description of source [sourceId] was captured (the entry's time) for [reason]; frozen at [frozenAt]. */
data class TimelineSnapshotDto(
    val sourceId: UUID,
    val reason: SnapshotCaptureReason,
    val frozenAt: Instant?,
)

/** An interview starting at the entry's time, [localStart] on the clocks of [timeZone]; [outcome] once known. */
data class TimelineInterviewDto(
    val type: InterviewKind,
    val localStart: LocalDateTime,
    val timeZone: String,
    val outcome: InterviewResultKind?,
) {
    companion object {
        fun from(entry: TimelineEntry.InterviewPlanned): TimelineInterviewDto =
            TimelineInterviewDto(
                entry.type.mapByName(),
                LocalDateTime.ofInstant(entry.occurredAt, entry.timeZone),
                entry.timeZone.id,
                entry.outcome?.mapByName(),
            )
    }
}

/** A task linked to the application, added at the entry's time; done at [completedAt] if so. */
data class TimelineTaskDto(
    val title: String,
    val completedAt: Instant?,
) {
    override fun toString(): String = "TimelineTaskDto(completedAt=$completedAt)"
}

/**
 * JSON body of `GET /api/applications/{id}/timeline`: [entries] newest first; [nextCursor] fetches the following
 * page (as `cursor`) and is absent on the last one.
 */
data class ApplicationTimelineResponse(
    val entries: List<TimelineEntryResponse>,
    val nextCursor: String?,
) {
    companion object {
        fun from(page: TimelinePage): ApplicationTimelineResponse =
            ApplicationTimelineResponse(page.entries.map(TimelineEntryResponse::from), page.next?.token())
    }
}
