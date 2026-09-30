// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import io.github.scriptibus.jofi.tasks.domain.TaskField
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
                problem(
                    HttpStatus.CONFLICT,
                    INVALID_TRANSITION,
                    "A task cannot move from ${failure.from} to ${failure.to}",
                )
            }

            is TaskResult.Unconfirmed -> {
                Confirmations.problem(failure.outcome)
            }

            is TaskResult.StorageFailure -> {
                problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "Tasks cannot be stored right now")
            }
        }

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
