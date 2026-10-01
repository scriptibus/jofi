// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDashboard
import io.github.scriptibus.jofi.tasks.domain.TaskGroup
import io.github.scriptibus.jofi.tasks.domain.TaskInput
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTimingInput
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// Task titles and notes are the user's words and may describe other people: DTOs that hold them print neither.

/**
 * When a task is due, as entered (ADR-0049): exactly one of [localDue], the agreed wall-clock time
 * (`2026-10-05T10:00`) in [timeZone], or a [bucket] relative to today in [timeZone] (the zone the user is in; an IANA
 * id such as `Europe/Berlin` or an offset such as `+02:00`). Violations name `timing` (neither or both),
 * `timing.localDue` and `timing.timeZone`.
 */
data class TaskTimingRequest(
    val timeZone: String,
    val bucket: TaskBucket? = null,
    val localDue: LocalDateTime? = null,
) {
    fun toInput(): TaskTimingInput = TaskTimingInput(timeZone, bucket?.toEnum(), localDue)
}

/** What a task is about: an application, a company or a contact by id. One that does not exist is `link.id`. */
data class TaskLinkDto(
    val type: TaskLinkType,
    val id: UUID,
) {
    fun toLink(): TaskLink =
        when (type) {
            TaskLinkType.APPLICATION -> ApplicationRef(id)
            TaskLinkType.COMPANY -> CompanyRef(id)
            TaskLinkType.CONTACT -> ContactRef(id)
        }

    companion object {
        fun from(link: TaskLink): TaskLinkDto =
            when (link) {
                is ApplicationRef -> TaskLinkDto(TaskLinkType.APPLICATION, link.value)
                is CompanyRef -> TaskLinkDto(TaskLinkType.COMPANY, link.value)
                is ContactRef -> TaskLinkDto(TaskLinkType.CONTACT, link.value)
            }
    }
}

/** A task as the user enters it. Text is trimmed and blank notes count as absent; violations answer 400. */
data class TaskRequest(
    val title: String,
    val timing: TaskTimingRequest,
    val link: TaskLinkDto? = null,
    /** Markdown. */
    val notes: String? = null,
) {
    fun toInput(): TaskInput = TaskInput(title, timing.toInput(), link?.toLink(), notes)

    override fun toString(): String = "TaskRequest(timing=$timing, link=${link?.type})"
}

/** Body of `PUT /api/tasks/{id}`: all details and the version they are based on. */
data class UpdateTaskRequest(
    val details: TaskRequest,
    val basedOnVersion: Long,
)

/** Body of the state changes (complete, reopen, accept, dismiss): the version they are based on. */
data class TaskVersionRequest(
    val basedOnVersion: Long,
)

/**
 * When a task is due: exactly one of [dueAt] (the instant; order and count down by it, with [localDue] in
 * [timeZone], the zone it was planned in) or [span] (a day, the week from Monday or the month from [startsOn], all up
 * to the day before [endsBefore]; someday has neither).
 */
data class TaskTimingResponse(
    val dueAt: Instant? = null,
    val localDue: LocalDateTime? = null,
    val timeZone: String? = null,
    val span: TaskSpan? = null,
    val startsOn: LocalDate? = null,
    val endsBefore: LocalDate? = null,
) {
    companion object {
        fun from(timing: TaskTiming): TaskTimingResponse =
            when (timing) {
                is TaskTiming.Exact -> {
                    TaskTimingResponse(timing.dueAt, timing.localDue, timing.zone.id)
                }

                is TaskTiming.Bucket -> {
                    TaskTimingResponse(
                        span = timing.span.toEnum(),
                        startsOn = timing.startsOn,
                        endsBefore = timing.endsBefore,
                    )
                }
            }
    }
}

/**
 * One task. [suggestionRule] names the rule that suggested it (`follow-up`); [completedAt] is set while it is done;
 * [version] goes back as `basedOnVersion` with the next change. Render the notes sanitised.
 */
data class TaskResponse(
    val id: UUID,
    val title: String,
    val timing: TaskTimingResponse,
    val link: TaskLinkDto?,
    val notes: String?,
    val origin: TaskOriginKind,
    val suggestionRule: String?,
    val status: TaskStatus,
    val completedAt: Instant?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "TaskResponse(id=$id, status=$status, version=$version)"

    companion object {
        fun from(task: Task): TaskResponse {
            val details = task.details
            val origin = task.origin
            return TaskResponse(
                task.id.value,
                details.title,
                TaskTimingResponse.from(details.timing),
                details.link?.let(TaskLinkDto::from),
                details.notes,
                originKind(origin),
                (origin as? TaskOrigin.Suggested)?.rule,
                task.state.toEnum(),
                task.completedAt,
                task.version,
                task.createdAt,
                task.updatedAt,
            )
        }

        private fun originKind(origin: TaskOrigin): TaskOriginKind =
            when (origin) {
                TaskOrigin.Manual -> TaskOriginKind.MANUAL
                TaskOrigin.Chat -> TaskOriginKind.CHAT
                is TaskOrigin.Suggested -> TaskOriginKind.SUGGESTED
            }
    }
}

/** JSON body of a plain task list (the suggestions). */
data class TaskListResponse(
    val tasks: List<TaskResponse>,
) {
    companion object {
        fun from(tasks: List<Task>): TaskListResponse = TaskListResponse(tasks.map(TaskResponse::from))
    }
}

/** One group of the task list with its tasks, soonest first. */
data class TaskGroupResponse(
    val group: TaskDueGroup,
    val tasks: List<TaskResponse>,
) {
    companion object {
        fun from(group: TaskGroup): TaskGroupResponse =
            TaskGroupResponse(group.kind.toEnum(), group.tasks.map(TaskResponse::from))
    }
}

/** JSON body of `GET /api/tasks`: every group in [TaskDueGroup]'s order, empty ones included. */
data class TaskGroupListResponse(
    val groups: List<TaskGroupResponse>,
) {
    companion object {
        fun from(groups: List<TaskGroup>): TaskGroupListResponse =
            TaskGroupListResponse(groups.map(TaskGroupResponse::from))
    }
}

/**
 * JSON body of `GET /api/dashboard/tasks` (ADR-0052): the open tasks that are [overdue], and those due today or in
 * the six days after it ([upcoming]), each soonest first, on the viewer's calendar.
 */
data class TaskDashboardResponse(
    val overdue: List<TaskResponse>,
    val upcoming: List<TaskResponse>,
) {
    companion object {
        fun from(dashboard: TaskDashboard): TaskDashboardResponse =
            TaskDashboardResponse(
                dashboard.overdue.map(TaskResponse::from),
                dashboard.upcoming.map(TaskResponse::from),
            )
    }
}
