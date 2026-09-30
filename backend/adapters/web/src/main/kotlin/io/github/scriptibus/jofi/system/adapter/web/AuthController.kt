// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.application.ChangePasswordUseCase
import io.github.scriptibus.jofi.system.application.CompleteFirstRunUseCase
import io.github.scriptibus.jofi.system.application.GetAuthStatusUseCase
import io.github.scriptibus.jofi.system.application.LogInUseCase
import io.github.scriptibus.jofi.system.domain.AuthStatusResult
import io.github.scriptibus.jofi.system.domain.FirstRunResult
import io.github.scriptibus.jofi.system.domain.LoginResult
import io.github.scriptibus.jofi.system.domain.PasswordChangeRequest
import io.github.scriptibus.jofi.system.domain.PasswordChangeResult
import io.github.scriptibus.jofi.system.domain.SessionRef
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Single-user login (ADR-0017). Only `GET /session`, `POST /login` and `POST /first-run` are open
 * without a session (see the security filter chain); every unsafe request needs the CSRF token.
 * Failed attempts are logged without password, token or client address.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val getAuthStatus: GetAuthStatusUseCase,
    private val completeFirstRun: CompleteFirstRunUseCase,
    private val logIn: LogInUseCase,
    private val changePassword: ChangePasswordUseCase,
) {
    /** Whether first run or login comes next; also hands out the CSRF cookie the SPA needs. */
    @GetMapping("/session")
    fun getAuthSession(request: HttpServletRequest): AuthSessionResponse {
        SessionSecurity.issueCsrfToken(request)
        return when (val result = getAuthStatus.execute()) {
            is AuthStatusResult.Success -> {
                AuthSessionResponse(
                    setUp = result.status.setUp,
                    authenticated = request.userPrincipal != null,
                    setupTokenRequired = result.status.setupTokenRequired,
                )
            }

            AuthStatusResult.StorageFailure -> {
                throw AuthProblems.unavailable()
            }
        }
    }

    /** Chooses the password while none exists, then starts a session. Gone (404) afterwards. */
    @PostMapping("/first-run")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun completeFirstRun(
        @RequestBody body: FirstRunRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val result = completeFirstRun.execute(body.password, body.setupToken, client(request))
        if (result != FirstRunResult.Completed) throw AuthProblems.of(result)
        SessionSecurity.startSession(request, response)
    }

    /** Starts a session when the password is right. */
    @PostMapping("/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logIn(
        @RequestBody body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val result = logIn.execute(body.password, client(request))
        if (result != LoginResult.LoggedIn) throw AuthProblems.of(result)
        SessionSecurity.startSession(request, response)
    }

    /** Ends this session: invalidates it and clears the CSRF cookie (Spring Security's logout handlers). */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logOut(request: HttpServletRequest) {
        request.logout()
    }

    /** Changes the password (the current one is required); every other session ends. */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(
        @RequestBody body: ChangePasswordRequest,
        request: HttpServletRequest,
    ) {
        val sessionId = request.getSession(false)?.id ?: throw AuthProblems.notLoggedIn()
        val command =
            PasswordChangeRequest(body.currentPassword, body.newPassword, SessionRef(sessionId), client(request))
        val result = changePassword.execute(command)
        if (result != PasswordChangeResult.Changed) throw AuthProblems.of(result)
    }

    private fun client(request: HttpServletRequest) = ThrottleKey.Client(request.remoteAddr)
}
