// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.net.URI

/**
 * The problem responses a handler answers besides `default`, so the API contract documents them
 * (`OpenApiSpecApplication` reads this annotation; no swagger annotations on the main classpath).
 * The problem `type` URI tells cases with the same status apart (e.g. two kinds of 409).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ProblemResponses(
    vararg val value: ProblemKind,
)

/**
 * A second success status a handler may answer with the same body as its `@ResponseStatus` one (a handler that
 * returns a `ResponseEntity` picks the status at run time), so the contract documents both.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AlsoAnswers(
    val status: HttpStatus,
    val description: String,
)

/** A documented problem response: its status and, for [INVALID_INPUT], a body with violations. */
enum class ProblemKind(
    val status: HttpStatus,
    val description: String,
) {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "The input breaks the rules; `violations` names each field and problem"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Nothing with this id"),
    CONFLICT(HttpStatus.CONFLICT, "Conflicts with the current state; the problem `type` says how"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "The server is busy with similar requests; try again shortly"),
}

/** One broken rule of a request: the request [field] as clients name it, and the [problem] with it. */
data class FieldViolation(
    val field: String,
    val problem: String,
)

/**
 * The 400 answer for input that breaks the domain rules ([ProblemKind.INVALID_INPUT]), with every
 * violation at once. The violations are a property of their own, like `ConfirmationRequiredProblem`'s
 * token, so they are typed in the contract.
 */
class ValidationProblem(
    val violations: List<FieldViolation>,
) : ProblemDetail(HttpStatus.BAD_REQUEST.value()) {
    companion object {
        /** The exception a controller throws for [violations] of problem [type]. */
        fun of(
            type: String,
            violations: List<FieldViolation>,
        ): ErrorResponseException {
            val body = ValidationProblem(violations)
            body.type = URI.create(type)
            body.detail = "The input breaks ${violations.size} rule(s); see violations"
            return ErrorResponseException(HttpStatus.BAD_REQUEST, body, null)
        }
    }
}
