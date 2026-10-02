// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import io.github.scriptibus.jofi.tasks.domain.DoneTaskQuery
import io.github.scriptibus.jofi.tasks.domain.TaskField
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.net.URI

/**
 * How task and countdown failures answer over REST (ADR-0041): the status and a `urn:jofi:problem:tasks:*` type per
 * case. No titles or notes in `detail`.
 */
object TaskProblems {
    const val INVALID = "urn:jofi:problem:tasks:invalid-task"
    const val NOT_FOUND = "urn:jofi:problem:tasks:task-not-found"
    const val COUNTDOWN_NOT_FOUND = "urn:jofi:problem:tasks:countdown-not-found"
    const val VERSION_CONFLICT = "urn:jofi:problem:tasks:version-conflict"
    const val INVALID_TRANSITION = "urn:jofi:problem:tasks:invalid-transition"
    const val UNAVAILABLE = "urn:jofi:problem:tasks:storage-unavailable"

    /** The query parameter naming the viewer's zone of a list. */
    const val VIEWER_ZONE = "timeZone"

    fun of(failure: TaskResult.Failure): ErrorResponseException =
        when (failure) {
            is TaskResult.Invalid -> {
                ValidationProblem.of(
                    INVALID,
                    failure.violations.map { FieldViolation(apiName(it.field), it.problem.name) },
                )
            }

            TaskResult.NotFound -> {
                problem(HttpStatus.NOT_FOUND, NOT_FOUND, "No task with this id")
            }

            TaskResult.CountdownNotFound -> {
                problem(HttpStatus.NOT_FOUND, COUNTDOWN_NOT_FOUND, "No countdown with this id")
            }

            TaskResult.VersionConflict -> {
                problem(HttpStatus.CONFLICT, VERSION_CONFLICT, "It changed meanwhile; reload it and retry")
            }

            is TaskResult.InvalidTransition -> {
                problem(HttpStatus.CONFLICT, INVALID_TRANSITION, transitionDetail(failure))
            }

            is TaskResult.Unconfirmed -> {
                Confirmations.problem(failure.outcome)
            }

            is TaskResult.StorageFailure -> {
                problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "Tasks cannot be stored right now")
            }
        }

    /** A move to the state the task is in already is an accept of a task that never was a suggestion. */
    private fun transitionDetail(failure: TaskResult.InvalidTransition): String =
        if (failure.from == failure.to) {
            "This task is not a suggestion: only suggestions can be accepted or dismissed"
        } else {
            "A task cannot move from ${failure.from} to ${failure.to}"
        }

    /** The viewer's zone of a list (query parameter `timeZone`) is not one Java knows. */
    fun invalidViewerZone(): ErrorResponseException =
        ValidationProblem.of(INVALID, listOf(FieldViolation(VIEWER_ZONE, TaskProblem.INVALID_TIME_ZONE.name)))

    /** The 400 for the `page` or `size` of the done tasks that `DoneTaskQuery.of` refused. */
    fun invalidDonePage(
        page: Int,
        size: Int,
    ): ErrorResponseException =
        ValidationProblem.of(
            INVALID,
            listOfNotNull(
                FieldViolation("page", TaskProblem.OUT_OF_RANGE.name).takeIf { page < 0 },
                FieldViolation("size", TaskProblem.OUT_OF_RANGE.name).takeIf { size !in 1..DoneTaskQuery.MAX_SIZE },
            ),
        )

    /** `page` or `size` of the done tasks is not a whole number (400 in the documented shape, naming it). */
    fun notANumber(parameter: String): ErrorResponseException =
        ValidationProblem.of(INVALID, listOf(FieldViolation(parameter, "INVALID")))

    /** The request field a violation belongs to, e.g. `timing.localDue`, so clients can show it there. */
    fun apiName(field: TaskField): String =
        when (field) {
            TaskField.TITLE -> "title"
            TaskField.NOTES -> "notes"
            TaskField.TIMING -> "timing"
            TaskField.DUE -> "timing.localDue"
            TaskField.TIME_ZONE -> "timing.timeZone"
            TaskField.LINK -> "link.id"
            TaskField.TARGET_DATE -> "targetDate"
        }

    private fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ErrorResponseException {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply { this.type = URI.create(type) }
        return ErrorResponseException(status, body, null)
    }
}
