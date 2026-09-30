// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CsrfException
import tools.jackson.databind.json.JsonMapper

/**
 * Answers the security filter chain's refusals with RFC 9457 problem details, the one error contract
 * of the API (ADR-0033): 401 without a session, 403 for a missing or wrong CSRF token or a denied
 * request. The body names the problem type only; it never echoes request data.
 */
class SecurityProblemHandler(
    private val json: JsonMapper,
) : AuthenticationEntryPoint,
    AccessDeniedHandler {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) = write(response, HttpStatus.UNAUTHORIZED, AuthProblems.NOT_LOGGED_IN, "Log in to use this endpoint")

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) = if (accessDeniedException is CsrfException) {
        write(response, HttpStatus.FORBIDDEN, AuthProblems.CSRF, "Missing or invalid CSRF token")
    } else {
        write(response, HttpStatus.FORBIDDEN, AuthProblems.ACCESS_DENIED, "Access denied")
    }

    /** 503 when the session cannot be checked; the session itself stays. */
    fun unavailable(response: HttpServletResponse) =
        write(
            response,
            HttpStatus.SERVICE_UNAVAILABLE,
            AuthProblems.UNAVAILABLE,
            "Authentication is temporarily unavailable",
        )

    private fun write(
        response: HttpServletResponse,
        status: HttpStatus,
        type: String,
        detail: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        val body =
            linkedMapOf(
                "type" to type,
                "title" to status.reasonPhrase,
                "status" to status.value(),
                "detail" to detail,
            )
        json.writeValue(response.outputStream, body)
    }
}
