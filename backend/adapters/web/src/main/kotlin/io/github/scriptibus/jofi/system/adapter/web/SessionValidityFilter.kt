// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.application.GetSessionAccountUseCase
import io.github.scriptibus.jofi.system.domain.AccountLookup
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Checks a logged-in session before authorization runs (ADR-0035) and ends it when
 * - it belongs to another account than the current one (the account was reset or deleted), or
 * - it was started more than [maxAge] ago, however active it was (absolute lifetime since login).
 * The request then continues without a session, so protected endpoints answer 401. When the account
 * cannot be read, this request is refused with 503 but the session stays: a database hiccup must not
 * log the user out.
 */
class SessionValidityFilter(
    private val sessionAccount: GetSessionAccountUseCase,
    private val maxAge: Duration,
    private val clock: Clock,
    private val problems: SecurityProblemHandler,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val session = request.getSession(false)
        val validity = if (session != null && isLoggedIn(session)) validity(session) else Validity.VALID
        when (validity) {
            Validity.VALID -> {
                filterChain.doFilter(request, response)
            }

            Validity.UNKNOWN -> {
                problems.unavailable(response)
            }

            Validity.ENDED -> {
                session?.invalidate()
                SecurityContextHolder.getContextHolderStrategy().clearContext()
                logger.info("Ended a session that outlived its account or its maximum age")
                filterChain.doFilter(request, response)
            }
        }
    }

    private fun isLoggedIn(session: HttpSession): Boolean =
        session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null

    private fun validity(session: HttpSession): Validity {
        val loggedInAt =
            (
                session.getAttribute(
                    SessionSecurity.LOGIN_TIME_ATTRIBUTE,
                ) as? Long
            )?.let(Instant::ofEpochMilli)
        if (loggedInAt == null || clock.instant().isAfter(loggedInAt.plus(maxAge))) return Validity.ENDED
        return when (val current = sessionAccount.execute()) {
            AccountLookup.StorageFailure -> {
                Validity.UNKNOWN
            }

            AccountLookup.None -> {
                Validity.ENDED
            }

            is AccountLookup.Found -> {
                if (current.accountId.value.toString() == session.getAttribute(SessionSecurity.ACCOUNT_ATTRIBUTE)) {
                    Validity.VALID
                } else {
                    Validity.ENDED
                }
            }
        }
    }

    private enum class Validity { VALID, ENDED, UNKNOWN }
}
