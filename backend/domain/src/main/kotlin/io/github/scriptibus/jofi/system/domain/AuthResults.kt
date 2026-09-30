// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import java.time.Duration

/** What the login screen needs to know before anyone is logged in. */
data class AuthStatus(
    /** A password has been chosen (first run is done). */
    val setUp: Boolean,
    /** First run needs the one-time setup token from the data volume (Jofi is exposed on a network). */
    val setupTokenRequired: Boolean,
)

/** Outcome of reading the [AuthStatus]. */
sealed interface AuthStatusResult {
    data class Success(
        val status: AuthStatus,
    ) : AuthStatusResult

    data object StorageFailure : AuthStatusResult
}

/** Outcome of the first-run password setup. */
sealed interface FirstRunResult {
    /** The password is set; the caller starts a session. */
    data object Completed : FirstRunResult

    /** A password exists already: first run is over for good. */
    data object AlreadySetUp : FirstRunResult

    /** Jofi is exposed and the setup token is missing or wrong. */
    data object InvalidSetupToken : FirstRunResult

    data class WeakPassword(
        val violation: PasswordPolicyCheck.Violation,
    ) : FirstRunResult

    data class Throttled(
        val retryAfter: Duration,
    ) : FirstRunResult

    data object StorageFailure : FirstRunResult
}

/** Outcome of a login attempt. Deliberately says nothing about why a password was wrong. */
sealed interface LoginResult {
    /** The password is right; the caller starts a session. */
    data object LoggedIn : LoginResult

    data object InvalidCredentials : LoginResult

    /** No password has been set yet; first run comes first. */
    data object NotSetUp : LoginResult

    data class Throttled(
        val retryAfter: Duration,
    ) : LoginResult

    data object StorageFailure : LoginResult
}

/** What a password change needs from the request; [toString] hides both passwords. */
class PasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String,
    /** The session that asks; it stays logged in while every other session ends. */
    val session: SessionRef,
    val client: ThrottleKey.Client,
) {
    override fun toString(): String = "PasswordChangeRequest(***)"
}

/** Outcome of a password change. */
sealed interface PasswordChangeResult {
    /** Changed; every other session of the user has ended. */
    data object Changed : PasswordChangeResult

    data object WrongCurrentPassword : PasswordChangeResult

    data class WeakPassword(
        val violation: PasswordPolicyCheck.Violation,
    ) : PasswordChangeResult

    data object NotSetUp : PasswordChangeResult

    data class Throttled(
        val retryAfter: Duration,
    ) : PasswordChangeResult

    data object StorageFailure : PasswordChangeResult
}

/**
 * The server-side id of a login session. A bearer credential while it lives, so [toString] hides it.
 */
class SessionRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A session id must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is SessionRef && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SessionRef(***)"
}

/** Outcome of a side effect around authentication (setup token file, ending sessions). */
sealed interface AuthSideEffectResult {
    data object Success : AuthSideEffectResult

    /** The effect could not be completed, e.g. the token file could not be written. */
    data object Failure : AuthSideEffectResult
}
