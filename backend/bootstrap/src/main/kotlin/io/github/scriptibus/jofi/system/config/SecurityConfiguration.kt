// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.system.adapter.web.SecurityProblemHandler
import io.github.scriptibus.jofi.system.adapter.web.SessionSecurity
import io.github.scriptibus.jofi.system.adapter.web.SessionValidityFilter
import io.github.scriptibus.jofi.system.application.GetSessionAccountUseCase
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.session.web.http.DefaultCookieSerializer
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/**
 * The security filter chain of the single-user login (ADR-0017, threat model T5). Every request
 * under `/api/` needs a session, except the three endpoints the login screen calls before one exists
 * ([PUBLIC_API]); health stays open for the container healthcheck. The SPA shell and its assets are
 * public, since they hold no data. Every unsafe request needs the CSRF token (cookie + header).
 * Login and logout are REST endpoints (`AuthController`), so form login, HTTP basic and the logout
 * filter are off; `request.logout()` still runs the configured logout handlers. `SessionValidityFilter`
 * ends sessions of a reset account and sessions older than `jofi.auth.session-max-age`.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        json: JsonMapper,
        sessionAccount: GetSessionAccountUseCase,
        clock: Clock,
        @Value("\${jofi.auth.session-max-age}") sessionMaxAge: Duration,
    ): SecurityFilterChain {
        val problems = SecurityProblemHandler(json)
        // Not a bean: as one, Spring Boot would also register it outside the security filter chain.
        val sessionValidity = SessionValidityFilter(sessionAccount, sessionMaxAge, clock, problems)
        http
            .addFilterBefore(sessionValidity, AnonymousAuthenticationFilter::class.java)
            .authorizeHttpRequests { requests ->
                PUBLIC_API.forEach { (method, path) -> requests.requestMatchers(method, path).permitAll() }
                requests.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                requests.requestMatchers("/actuator/**").denyAll()
                requests.requestMatchers("/api/**").authenticated()
                requests.anyRequest().permitAll()
            }.csrf { csrf -> csrf.spa().csrfTokenRepository(SessionSecurity.csrfTokenRepository()) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .requestCache { it.disable() }
            .logout { it.logoutRequestMatcher(RequestMatcher { false }) }
            .exceptionHandling {
                it.authenticationEntryPoint(problems)
                it.accessDeniedHandler(problems)
            }
        return http.build()
    }

    /**
     * The session cookie, set explicitly instead of through `server.servlet.session.cookie.*`, which
     * Spring Boot only applies with an embedded server: `HttpOnly` (scripts cannot read it),
     * `SameSite=Lax`, and `Secure` whenever the request came in over HTTPS (`useSecureCookie` unset).
     */
    @Bean
    fun cookieSerializer(): DefaultCookieSerializer =
        DefaultCookieSerializer().apply {
            setUseHttpOnlyCookie(true)
            setSameSite("Lax")
            setCookiePath("/")
        }

    companion object {
        /** The only endpoints under `/api/` that answer without a session (asserted by a test). */
        val PUBLIC_API: List<Pair<HttpMethod, String>> =
            listOf(
                HttpMethod.GET to "/api/auth/session",
                HttpMethod.POST to "/api/auth/login",
                HttpMethod.POST to "/api/auth/first-run",
            )
    }
}
