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
 * Ends a logged-in session that is no longer valid, before authorization runs (ADR-0035):
 * - it belongs to another account than the current one (the account was reset or deleted), or
 * - it is older than [maxAge] since login, however active it was (absolute lifetime).
 * The request then continues without a session, so protected endpoints answer 401. When the account
 * cannot be read the session is ended as well: fail closed.
 */
class SessionValidityFilter(
    private val sessionAccount: GetSessionAccountUseCase,
    private val maxAge: Duration,
    private val clock: Clock,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val session = request.getSession(false)
        if (session != null && isLoggedIn(session) && !isValid(session)) {
            session.invalidate()
            SecurityContextHolder.getContextHolderStrategy().clearContext()
            logger.info("Ended a session that outlived its account or its maximum age")
        }
        filterChain.doFilter(request, response)
    }

    private fun isLoggedIn(session: HttpSession): Boolean =
        session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null

    private fun isValid(session: HttpSession): Boolean {
        val loggedInAt = Instant.ofEpochMilli(session.creationTime)
        if (clock.instant().isAfter(loggedInAt.plus(maxAge))) return false
        val current = sessionAccount.execute()
        return current is AccountLookup.Found &&
            current.accountId.value.toString() == session.getAttribute(SessionSecurity.ACCOUNT_ATTRIBUTE)
    }
}
