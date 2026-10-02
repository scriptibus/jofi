// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * A query parameter that cannot be read as its type (`page=abc`, an unknown `direction`) answers the documented
 * `ValidationProblem` naming the parameter, like a value that is read but out of range, instead of a problem without
 * violations. The value is never echoed.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class InvalidParameterAdvice {
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidParameter(exception: MethodArgumentTypeMismatchException): ResponseEntity<ProblemDetail> {
        val problem = ValidationProblem.of(TYPE, listOf(FieldViolation(exception.name, INVALID)))
        return ResponseEntity.status(problem.statusCode).body(problem.body)
    }

    private companion object {
        const val TYPE = "urn:jofi:problem:shared:invalid-parameter"
        const val INVALID = "INVALID"
    }
}
