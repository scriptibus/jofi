// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import java.time.Duration

/**
 * The current password, entered again to confirm a sensitive action (a password change, a backup
 * export or restore), and the client it came from for the backoff (ADR-0035). [toString] hides it.
 */
class PasswordConfirmation(
    val password: String,
    val client: ThrottleKey.Client,
) {
    override fun toString(): String = "PasswordConfirmation(***)"
}

/** Outcome of checking the current password, throttled like a login. */
sealed interface PasswordCheckResult {
    data object Verified : PasswordCheckResult

    data object Wrong : PasswordCheckResult

    data object NotSetUp : PasswordCheckResult

    data class Throttled(
        val retryAfter: Duration,
    ) : PasswordCheckResult

    data object StorageFailure : PasswordCheckResult
}
