// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.domain.FirstRunResult
import io.github.scriptibus.jofi.system.domain.LoginResult
import io.github.scriptibus.jofi.system.domain.PasswordChangeResult
import io.github.scriptibus.jofi.system.domain.PasswordCheckResult
import io.github.scriptibus.jofi.system.domain.PasswordPolicyCheck
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.net.URI
import java.time.Duration

/** Problem types of the authentication API, so the login screen can tell the cases apart. */
object AuthProblems {
    private const val PREFIX = "urn:jofi:problem:system:"
    const val NOT_LOGGED_IN = "${PREFIX}not-logged-in"
    const val CSRF = "${PREFIX}csrf"
    const val ACCESS_DENIED = "${PREFIX}access-denied"
    const val INVALID_CREDENTIALS = "${PREFIX}invalid-credentials"
    const val THROTTLED = "${PREFIX}login-throttled"
    const val NOT_SET_UP = "${PREFIX}not-set-up"
    const val ALREADY_SET_UP = "${PREFIX}already-set-up"
    const val INVALID_SETUP_TOKEN = "${PREFIX}invalid-setup-token"
    const val WEAK_PASSWORD = "${PREFIX}weak-password"
    const val UNAVAILABLE = "${PREFIX}auth-unavailable"
    const val OTHER_SESSIONS_REMAIN = "${PREFIX}other-sessions-remain"

    /** The problem for a failed login; the failure is logged without password or client address. */
    fun of(failure: LoginResult): ErrorResponseException =
        when (failure) {
            LoginResult.InvalidCredentials -> {
                logger.warn("Login failed: wrong password")
                problem(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, "Wrong password")
            }

            LoginResult.NotSetUp -> {
                problem(HttpStatus.CONFLICT, NOT_SET_UP, "No password is set yet; complete first run")
            }

            is LoginResult.Throttled -> {
                throttled(failure.retryAfter)
            }

            LoginResult.StorageFailure, is LoginResult.LoggedIn -> {
                unavailable()
            }
        }

    /** The problem for a failed first run. */
    fun of(failure: FirstRunResult): ErrorResponseException =
        when (failure) {
            FirstRunResult.AlreadySetUp -> {
                problem(HttpStatus.NOT_FOUND, ALREADY_SET_UP, "First run is complete")
            }

            FirstRunResult.InvalidSetupToken -> {
                logger.warn("First run refused: missing or wrong setup token")
                problem(HttpStatus.FORBIDDEN, INVALID_SETUP_TOKEN, "Missing or wrong setup token")
            }

            is FirstRunResult.WeakPassword -> {
                weakPassword(failure.violation)
            }

            is FirstRunResult.Throttled -> {
                throttled(failure.retryAfter)
            }

            FirstRunResult.StorageFailure, is FirstRunResult.Completed -> {
                unavailable()
            }
        }

    /** The problem for a failed password change. */
    fun of(failure: PasswordChangeResult): ErrorResponseException =
        when (failure) {
            PasswordChangeResult.WrongCurrentPassword -> {
                logger.warn("Password change refused: wrong current password")
                problem(HttpStatus.FORBIDDEN, INVALID_CREDENTIALS, "Wrong current password")
            }

            is PasswordChangeResult.WeakPassword -> {
                weakPassword(failure.violation)
            }

            PasswordChangeResult.NotSetUp -> {
                problem(HttpStatus.CONFLICT, NOT_SET_UP, "No password is set yet")
            }

            is PasswordChangeResult.Throttled -> {
                throttled(failure.retryAfter)
            }

            PasswordChangeResult.ChangedButOtherSessionsRemain -> {
                logger.error("Password changed, but ending the other sessions failed")
                problem(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    OTHER_SESSIONS_REMAIN,
                    "The password was changed, but other sessions could not be ended; change it again",
                )
            }

            PasswordChangeResult.StorageFailure, PasswordChangeResult.Changed -> {
                unavailable()
            }
        }

    /** The problem for a current password that did not confirm a sensitive action (backup export, restore). */
    fun of(failure: PasswordCheckResult): ErrorResponseException =
        when (failure) {
            PasswordCheckResult.Wrong -> {
                logger.warn("Sensitive action refused: wrong current password")
                problem(HttpStatus.FORBIDDEN, INVALID_CREDENTIALS, "Wrong current password")
            }

            PasswordCheckResult.NotSetUp -> {
                problem(HttpStatus.CONFLICT, NOT_SET_UP, "No password is set yet")
            }

            is PasswordCheckResult.Throttled -> {
                throttled(failure.retryAfter)
            }

            PasswordCheckResult.StorageFailure, PasswordCheckResult.Verified -> {
                unavailable()
            }
        }

    fun notLoggedIn(): ErrorResponseException = problem(HttpStatus.UNAUTHORIZED, NOT_LOGGED_IN, "Log in first")

    fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ErrorResponseException {
        val body = ProblemDetail.forStatusAndDetail(status, detail)
        body.type = URI.create(type)
        return ErrorResponseException(status, body, null)
    }

    /** 429 with `Retry-After` in whole seconds (rounded up). */
    fun throttled(retryAfter: Duration): ErrorResponseException {
        logger.warn("Password check refused: backing off after repeated failures")
        val seconds = (retryAfter.toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
        val exception =
            problem(HttpStatus.TOO_MANY_REQUESTS, THROTTLED, "Too many failed attempts; retry in $seconds s")
        exception.headers.set(HttpHeaders.RETRY_AFTER, seconds.toString())
        return exception
    }

    fun weakPassword(violation: PasswordPolicyCheck.Violation): ErrorResponseException {
        val detail =
            when (violation) {
                is PasswordPolicyCheck.TooShort -> "The password needs at least ${violation.minLength} characters"
                is PasswordPolicyCheck.TooLong -> "The password may have at most ${violation.maxLength} characters"
            }
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, WEAK_PASSWORD, detail)
    }

    fun unavailable(): ErrorResponseException =
        problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "Authentication is temporarily unavailable")

    private const val MILLIS_PER_SECOND = 1000L
    private val logger: Logger = LoggerFactory.getLogger(AuthProblems::class.java)
}
