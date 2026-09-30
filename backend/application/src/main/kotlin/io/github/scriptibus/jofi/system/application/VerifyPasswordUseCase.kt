// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.PasswordHasherPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.PasswordCheckResult
import io.github.scriptibus.jofi.system.domain.PasswordConfirmation
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import java.time.Clock

/**
 * Checks the current password before a sensitive action (password change, backup export and
 * restore), so a hijacked session alone is not enough. Throttled exactly like a login (ADR-0035): the
 * attempt is charged before hashing and a correct password clears the counts.
 */
class VerifyPasswordUseCase(
    private val users: UserAccountPort,
    private val hasher: PasswordHasherPort,
    private val throttle: LoginThrottlePort,
    private val clock: Clock,
) {
    fun execute(confirmation: PasswordConfirmation): PasswordCheckResult {
        val decision = throttle.attemptFor(confirmation.client, clock.instant())
        if (decision is ThrottleDecision.Throttled) return PasswordCheckResult.Throttled(decision.retryAfter)
        return when (val found = users.find()) {
            is UserAccountStoreResult.Success -> {
                found.value?.let { check(it, confirmation) }
                    ?: PasswordCheckResult.NotSetUp
            }

            else -> {
                PasswordCheckResult.StorageFailure
            }
        }
    }

    private fun check(
        account: UserAccount,
        confirmation: PasswordConfirmation,
    ): PasswordCheckResult {
        val password = Password.submitted(confirmation.password)
        if (password == null || !hasher.matches(password, account.passwordHash)) return PasswordCheckResult.Wrong
        throttle.resetFor(confirmation.client)
        return PasswordCheckResult.Verified
    }
}
