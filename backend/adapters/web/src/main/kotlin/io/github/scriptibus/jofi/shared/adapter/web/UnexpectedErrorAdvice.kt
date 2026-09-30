// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Keeps the one error contract (RFC 9457 problem details) for failures nobody mapped: without it an
 * unexpected exception reaches Spring Boot's `/error` page, which answers with a different JSON shape.
 * Lowest precedence, so Spring's own `ProblemDetailsExceptionHandler` still answers framework errors
 * and `ErrorResponseException`s with their real status. The response carries no internal details.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class UnexpectedErrorAdvice {
    private val logger = LoggerFactory.getLogger(UnexpectedErrorAdvice::class.java)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ProblemDetail {
        logger.error("Unhandled exception while serving a request", exception)
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error")
    }
}
