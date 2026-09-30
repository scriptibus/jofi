// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Keeps the one error contract (RFC 9457 problem details) for failures nobody mapped: without it an
 * unexpected exception reaches Spring Boot's `/error` page, which answers with a different JSON shape.
 * Lowest precedence, so Spring's own `ProblemDetailsExceptionHandler` still answers framework errors.
 * Exceptions that already carry a status keep it: an `ErrorResponse` keeps its own problem, and an
 * exception annotated with `@ResponseStatus` gets that status. Everything else is a 500 without
 * internal details.
 *
 * Spring Security is not on the classpath yet. When authentication lands (#16), its
 * `AccessDeniedException` and `AuthenticationException` must be rethrown here so the security
 * filter chain answers them with 403/401 instead of a 500.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class UnexpectedErrorAdvice {
    private val logger = LoggerFactory.getLogger(UnexpectedErrorAdvice::class.java)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<ProblemDetail> {
        val declared = AnnotatedElementUtils.findMergedAnnotation(exception.javaClass, ResponseStatus::class.java)
        return when {
            exception is ErrorResponse -> ResponseEntity.status(exception.statusCode).body(exception.body)
            declared != null -> ResponseEntity.status(declared.code).body(declaredProblem(declared))
            else -> unexpected(exception)
        }
    }

    private fun declaredProblem(declared: ResponseStatus): ProblemDetail {
        val problem = ProblemDetail.forStatus(declared.code)
        problem.detail = declared.reason.ifBlank { null }
        return problem
    }

    private fun unexpected(exception: Exception): ResponseEntity<ProblemDetail> {
        logger.error("Unhandled exception while serving a request", exception)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error")
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem)
    }
}
