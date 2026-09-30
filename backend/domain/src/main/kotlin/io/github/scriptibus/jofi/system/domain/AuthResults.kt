// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import java.time.Duration

/** What the login screen needs to know before anyone is logged in. */
data class AuthStatus(
    /** A password has been chosen (first run is done). */
    val setUp: Boolean,
    /** First run needs the one-time setup token from the data volume (always, until first run is done). */
    val setupTokenRequired: Boolean,
)

/** The account a session may belong to, looked up on every request with a session. */
sealed interface AccountLookup {
    data class Found(
        val accountId: AccountId,
    ) : AccountLookup

    /** No account exists (before first run or after a password reset). */
    data object None : AccountLookup

    data object StorageFailure : AccountLookup
}

/** Outcome of the password reset requested at startup (`JOFI_RESET_PASSWORD`). */
sealed interface PasswordResetResult {
    /** The account is gone, every session has ended and a new setup token is issued. */
    data object Reset : PasswordResetResult

    /** There was no account to reset. */
    data object NothingToReset : PasswordResetResult

    /** The account is gone, but sessions or the token could not be handled; see the log. */
    data object ResetWithFailures : PasswordResetResult

    data object StorageFailure : PasswordResetResult
}

/** Outcome of reading the [AuthStatus]. */
sealed interface AuthStatusResult {
    data class Success(
        val status: AuthStatus,
    ) : AuthStatusResult

    data object StorageFailure : AuthStatusResult
}

/** Outcome of the first-run password setup. */
sealed interface FirstRunResult {
    /** The password is set; the caller starts a session bound to [accountId]. */
    data class Completed(
        val accountId: AccountId,
    ) : FirstRunResult

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
    /** The password is right; the caller starts a session bound to [accountId]. */
    data class LoggedIn(
        val accountId: AccountId,
    ) : LoginResult

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

    /**
     * Changed, but other sessions could not be ended and may still be valid (e.g. a stolen one).
     * The user has to retry or end them otherwise; this is never reported as success.
     */
    data object ChangedButOtherSessionsRemain : PasswordChangeResult

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
