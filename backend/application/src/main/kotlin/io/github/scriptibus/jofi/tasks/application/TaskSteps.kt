// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.RedactForAiUseCase
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.ai.AiRedaction
import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.PageValidation
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskField
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskSummary
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.github.scriptibus.jofi.tasks.domain.TaskValidation
import io.github.scriptibus.jofi.tasks.domain.TaskViolation
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

// How the task use cases chain their steps (as `ApplicationSteps`): a TaskResult per step, the first failure ends
// the chain.

/** "Now" at the precision of `timestamptz` (ADR-0041); also the instant a bucket is resolved with. */
internal fun Clock.storedNow(): Instant = instant().truncatedTo(ChronoUnit.MICROS)

/** Continues with [next] on success; a failure passes through unchanged. */
internal inline fun <T, R> TaskResult<T>.then(next: (T) -> TaskResult<R>): TaskResult<R> =
    when (this) {
        is TaskResult.Success -> next(value)
        is TaskResult.Failure -> this
    }

/** Runs [work] in one transaction that commits only on [TaskResult.Success]. */
internal fun <T> TransactionPort.inTaskTransaction(work: () -> TaskResult<T>): TaskResult<T> =
    inTransaction({ it is TaskResult.Success }, work)

/** The page asked for, or the violations of the page and size that are out of range. */
internal fun PageInput.toResult(): TaskResult<PageRequest> =
    when (val validation = validate()) {
        is PageValidation.Valid -> {
            TaskResult.Success(validation.request)
        }

        is PageValidation.Invalid -> {
            TaskResult.Invalid(
                listOfNotNull(
                    TaskViolation(TaskField.PAGE, TaskProblem.OUT_OF_RANGE).takeIf { validation.pageOutOfRange },
                    TaskViolation(TaskField.SIZE, TaskProblem.OUT_OF_RANGE).takeIf { validation.sizeOutOfRange },
                ),
            )
        }
    }

/**
 * The list entries of [tasks] for [audience] (ADR-0056): the user's own notes are cut as they are; for an AI the
 * "never send to AI" values go out of the whole notes first, so no excerpt ends inside one. Flags that cannot be read
 * fail the list (nothing reaches an AI).
 */
internal fun summariesOf(
    tasks: List<Task>,
    audience: NotesAudience,
    redaction: RedactForAiUseCase,
): TaskResult<List<TaskSummary>> =
    when (audience) {
        NotesAudience.USER -> {
            TaskResult.Success(tasks.map { TaskSummary.of(it) })
        }

        NotesAudience.AI -> {
            when (val redacted = redaction.execute(tasks.map { it.details.notes })) {
                AiRedaction.Unavailable -> {
                    TaskResult.StorageFailure("privacy flags")
                }

                is AiRedaction.Redacted -> {
                    TaskResult.Success(
                        tasks.zip(redacted.texts) { task, notes -> TaskSummary.of(task, notes) },
                    )
                }
            }
        }
    }

internal fun <T> TaskValidation<T>.toResult(): TaskResult<T> =
    when (this) {
        is TaskValidation.Valid -> TaskResult.Success(value)
        is TaskValidation.Invalid -> TaskResult.Invalid(violations)
    }

internal fun <T> TaskStoreResult<T>.toResult(): TaskResult<T> =
    when (this) {
        is TaskStoreResult.Success -> {
            TaskResult.Success(value)
        }

        TaskStoreResult.NotFound -> {
            TaskResult.NotFound
        }

        TaskStoreResult.VersionConflict -> {
            TaskResult.VersionConflict
        }

        // `task_application_fk`, `task_company_fk`, `task_contact_fk`: the link names something that does not exist.
        TaskStoreResult.LinkNotFound -> {
            TaskResult.Invalid(listOf(TaskViolation(TaskField.LINK, TaskProblem.NOT_FOUND)))
        }

        // Only suggesting (#95) can collide on `task_suggestion_unique`; these use cases never suggest.
        TaskStoreResult.SuggestionExists -> {
            TaskResult.StorageFailure("add suggestion")
        }

        // Only a proof for another target gets here, a bug of the use case; nothing was deleted.
        TaskStoreResult.NotConfirmed -> {
            TaskResult.StorageFailure("delete without matching proof")
        }

        is TaskStoreResult.StorageFailure -> {
            TaskResult.StorageFailure(operation)
        }
    }

/** The task if the caller based its change on its current version, else [TaskResult.VersionConflict]. */
internal fun Task.basedOn(version: Long): TaskResult<Task> =
    if (this.version == version) TaskResult.Success(this) else TaskResult.VersionConflict

/** [this] if [done] holds, else a storage failure of [operation] (the transaction then rolls back). */
internal fun <T> T.taskIf(
    done: Boolean,
    operation: String,
): TaskResult<T> = if (done) TaskResult.Success(this) else TaskResult.StorageFailure(operation)

/**
 * Reads the task, checks [basedOnVersion] (first, even for a no-op), applies [transition] [at] and stores the moved
 * task. The result is the task before and after; both are the same task when it was in the target state already, and
 * then nothing is stored.
 */
internal fun TaskRepositoryPort.move(
    id: TaskId,
    basedOnVersion: Long,
    transition: TaskTransition,
    at: Instant,
): TaskResult<Pair<Task, Task>> =
    findById(id).toResult().then { it.basedOn(basedOnVersion) }.then { current ->
        when (val change = current.apply(transition, at)) {
            TaskStateChange.Unchanged -> {
                TaskResult.Success(current to current)
            }

            is TaskStateChange.NotAllowed -> {
                TaskResult.InvalidTransition(change.from, change.to)
            }

            is TaskStateChange.Changed -> {
                update(change.task).toResult().then { TaskResult.Success(current to change.task) }
            }
        }
    }
