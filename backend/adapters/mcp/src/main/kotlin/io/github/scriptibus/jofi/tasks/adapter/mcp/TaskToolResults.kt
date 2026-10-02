// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskField
import io.github.scriptibus.jofi.tasks.domain.TaskGroup
import io.github.scriptibus.jofi.tasks.domain.TaskGroupKind
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskViolation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The task tools' results. A task's title and notes are [Untrusted]: a tool can write them (`create_task`), a
 * suggestion's title is made from an application's, and a prompt-injected model could store instructions in them for
 * later sessions (ADR-0053, amendment of #119). Ids, versions, state, timing and the link stay plain.
 */
data class TaskWords(
    val title: String,
    val notes: String?,
)

/** What a task is about: the `type` of `create_task`'s `link`. */
enum class TaskLinkKind { APPLICATION, COMPANY, CONTACT }

data class TaskLinkResult(
    val type: TaskLinkKind,
    val id: UUID,
)

/** Exactly one of [dueAt] (with its wall-clock [localDue] in [timeZone]) or [span] (with its days) is set. */
data class TaskTimingResult(
    val dueAt: Instant?,
    val localDue: LocalDateTime?,
    val timeZone: String?,
    val span: BucketSpan?,
    val startsOn: LocalDate?,
    val endsBefore: LocalDate?,
)

enum class TaskOriginKind { MANUAL, CHAT, SUGGESTED }

/** One task; [version] is what `complete_task`, `reopen_task` and `accept_task_suggestion` need to be based on. */
data class TaskDetailResult(
    val id: UUID,
    val version: Long,
    val status: TaskState,
    val origin: TaskOriginKind,
    val suggestionRule: String?,
    val timing: TaskTimingResult,
    val link: TaskLinkResult?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val task: Untrusted<TaskWords>,
) {
    companion object {
        fun from(task: Task): TaskDetailResult {
            val origin = task.origin
            return TaskDetailResult(
                task.id.value,
                task.version,
                task.state,
                originKind(origin),
                (origin as? TaskOrigin.Suggested)?.rule,
                timingOf(task.details.timing),
                task.details.link?.let(::linkOf),
                task.completedAt,
                task.createdAt,
                task.updatedAt,
                Untrusted(TaskWords(task.details.title, task.details.notes)),
            )
        }

        private fun originKind(origin: TaskOrigin) =
            when (origin) {
                TaskOrigin.Manual -> TaskOriginKind.MANUAL
                TaskOrigin.Chat -> TaskOriginKind.CHAT
                is TaskOrigin.Suggested -> TaskOriginKind.SUGGESTED
            }

        private fun timingOf(timing: TaskTiming) =
            when (timing) {
                is TaskTiming.Exact -> {
                    TaskTimingResult(timing.dueAt, timing.localDue, timing.zone.id, null, null, null)
                }

                is TaskTiming.Bucket -> {
                    TaskTimingResult(null, null, null, timing.span, timing.startsOn, timing.endsBefore)
                }
            }

        private fun linkOf(link: TaskLink) =
            when (link) {
                is ApplicationRef -> TaskLinkResult(TaskLinkKind.APPLICATION, link.value)
                is CompanyRef -> TaskLinkResult(TaskLinkKind.COMPANY, link.value)
                is ContactRef -> TaskLinkResult(TaskLinkKind.CONTACT, link.value)
            }
    }
}

data class TaskGroupResult(
    val group: TaskGroupKind,
    val tasks: List<TaskDetailResult>,
) {
    companion object {
        fun from(group: TaskGroup) = TaskGroupResult(group.kind, group.tasks.map(TaskDetailResult::from))
    }
}

/** The open tasks by due group, every group present in its order, empty ones included. */
data class TaskGroupsResult(
    val groups: List<TaskGroupResult>,
)

/** The suggestions waiting for a yes, newest first. */
data class TaskSuggestionsResult(
    val tasks: List<TaskDetailResult>,
)

/** The tool errors of the tasks: stable codes, no stored content. */
internal object TaskToolErrors {
    private const val NOT_A_SUGGESTION =
        "This task is not a suggestion: only suggestions can be accepted or dismissed."

    fun failure(failure: TaskResult.Failure): ToolAnswer.Error =
        when (failure) {
            is TaskResult.Invalid -> {
                ToolAnswer.Error(
                    "invalid-arguments",
                    "The task arguments are invalid.",
                    failure.violations.flatMap(::problemsOf),
                )
            }

            TaskResult.NotFound -> {
                ToolAnswer.Error("not-found", "No task has this id.")
            }

            TaskResult.VersionConflict -> {
                ToolProblems.versionConflict()
            }

            is TaskResult.InvalidTransition -> {
                ToolAnswer.Error("invalid-transition", transitionMessage(failure))
            }

            is TaskResult.StorageFailure -> {
                ToolAnswer.Error("unavailable", "Tasks cannot be used now.")
            }

            // No task tool deals with countdowns or deletes, so these cannot happen; a new failure breaks this `when`.
            TaskResult.CountdownNotFound, is TaskResult.Unconfirmed -> {
                ToolAnswer.Error("failed", "The task request could not be completed.")
            }
        }

    /**
     * A transition to the state the task is in already can only be an accept of a task that never was a suggestion
     * (a done or dismissed one is "unchanged" for its own transition): say that, since reading it again shows only
     * OPEN.
     */
    private fun transitionMessage(failure: TaskResult.InvalidTransition): String =
        if (failure.from == failure.to) {
            NOT_A_SUGGESTION
        } else {
            "A task cannot move from ${failure.from} to ${failure.to}. Read it again to see its state."
        }

    /**
     * The violation as the `create_task` arguments it belongs to. A timing that is missing or given twice concerns
     * `bucket` and `localDue` together; a bucket out of range, `bucket` alone.
     */
    fun problemsOf(violation: TaskViolation): List<ArgumentProblem> {
        val code = ToolProblems.problemCode(violation.problem.name)
        val arguments =
            when {
                violation.field != TaskField.TIMING -> listOf(argumentOf(violation.field))
                violation.problem == TaskProblem.OUT_OF_RANGE -> listOf("bucket")
                else -> listOf("bucket", "localDue")
            }
        return arguments.map { ArgumentProblem(it, code) }
    }

    private fun argumentOf(field: TaskField) =
        when (field) {
            TaskField.TITLE -> "title"
            TaskField.NOTES -> "notes"
            TaskField.TIMING -> "bucket"
            TaskField.DUE -> "localDue"
            TaskField.TIME_ZONE -> "timeZone"
            TaskField.LINK -> "link"
            TaskField.TARGET_DATE -> "targetDate"
        }
}
