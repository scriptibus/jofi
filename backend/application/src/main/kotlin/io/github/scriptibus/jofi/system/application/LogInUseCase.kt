// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.PasswordHasherPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.LoginResult
import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import java.time.Clock

/**
 * Checks the password for a new session. Repeated failures back off per client and for all clients
 * together (threat model T5); a throttled attempt is refused before any hashing happens.
 */
class LogInUseCase(
    private val users: UserAccountPort,
    private val hasher: PasswordHasherPort,
    private val throttle: LoginThrottlePort,
    private val clock: Clock,
) {
    fun execute(
        submittedPassword: String,
        client: ThrottleKey.Client,
    ): LoginResult {
        val decision = throttle.attemptFor(client, clock.instant())
        if (decision is ThrottleDecision.Throttled) return LoginResult.Throttled(decision.retryAfter)
        return when (val found = users.find()) {
            is UserAccountStoreResult.Success -> {
                found.value?.let { check(it, submittedPassword, client) }
                    ?: LoginResult.NotSetUp
            }

            else -> {
                LoginResult.StorageFailure
            }
        }
    }

    private fun check(
        account: UserAccount,
        submittedPassword: String,
        client: ThrottleKey.Client,
    ): LoginResult {
        val password = Password.submitted(submittedPassword)
        if (password == null || !hasher.matches(password, account.passwordHash)) return LoginResult.InvalidCredentials
        throttle.resetFor(client)
        return LoginResult.LoggedIn(account.accountId)
    }
}
