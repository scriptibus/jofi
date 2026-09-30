// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.domain.AccountId
import io.github.scriptibus.jofi.system.domain.UserAccount
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy
import org.springframework.security.web.csrf.CsrfToken
import java.time.Instant

/**
 * How a login becomes a session (ADR-0017). The login and first-run endpoints check the password
 * through their use cases and then call [startSession]; the security filter chain (bootstrap) uses
 * the same [csrfTokenRepository] settings, so both sides agree on the CSRF cookie.
 */
object SessionSecurity {
    /** The authority every session of the single user carries. */
    const val OWNER_ROLE = "ROLE_OWNER"

    private val contexts = HttpSessionSecurityContextRepository()
    private val sessionStrategy =
        CompositeSessionAuthenticationStrategy(
            listOf(
                // Session fixation: an id known before login is worthless afterwards.
                ChangeSessionIdAuthenticationStrategy(),
                // A CSRF token planted before login is replaced as well.
                CsrfAuthenticationStrategy(csrfTokenRepository()),
            ),
        )

    /**
     * CSRF for the SPA (Spring Security's `csrf.spa()`): the token travels in the `XSRF-TOKEN`
     * cookie, which the frontend reads and echoes in the `X-XSRF-TOKEN` header of every unsafe
     * request. `SameSite=Lax`; `Secure` whenever the request came in over HTTPS.
     */
    fun csrfTokenRepository(): CookieCsrfTokenRepository =
        CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
            setCookieCustomizer { cookie -> cookie.sameSite("Lax") }
        }

    /** The session attribute naming the account the session belongs to (`SessionValidityFilter`). */
    const val ACCOUNT_ATTRIBUTE = "jofi.accountId"

    /** The session attribute holding the login time (epoch milliseconds) for the absolute lifetime. */
    const val LOGIN_TIME_ATTRIBUTE = "jofi.loginTime"

    /**
     * Logs the single user in on this request: new session id, new CSRF token, stored context, and the
     * [accountId] the session belongs to.
     */
    fun startSession(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accountId: AccountId,
        loginTime: Instant = Instant.now(),
    ) {
        val authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                UserAccount.PRINCIPAL,
                null,
                AuthorityUtils.createAuthorityList(OWNER_ROLE),
            )
        sessionStrategy.onAuthentication(authentication, request, response)
        val holder = SecurityContextHolder.getContextHolderStrategy()
        val context = holder.createEmptyContext().apply { this.authentication = authentication }
        holder.context = context
        contexts.saveContext(context, request, response)
        val session = request.getSession(true)
        session.setAttribute(ACCOUNT_ATTRIBUTE, accountId.value.toString())
        session.setAttribute(LOGIN_TIME_ATTRIBUTE, loginTime.toEpochMilli())
        issueCsrfToken(request)
    }

    /** Makes sure the response carries a CSRF cookie, so the SPA can send its next unsafe request. */
    fun issueCsrfToken(request: HttpServletRequest) {
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
    }
}
