// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.PasswordHasherPort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.FirstRunResult
import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.PasswordPolicyCheck
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import java.time.Clock
import java.time.Instant

/**
 * First run: sets the password while none exists. When Jofi is exposed on a network it also needs
 * the setup token from the data volume. Afterwards first run answers [FirstRunResult.AlreadySetUp].
 */
class CompleteFirstRunUseCase(
    private val users: UserAccountPort,
    private val hasher: PasswordHasherPort,
    private val setupToken: SetupTokenPort,
    private val throttle: LoginThrottlePort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) {
    /** [client] is whoever sends the request; its failed attempts are throttled like logins. */
    fun execute(
        chosenPassword: String,
        token: String?,
        client: ThrottleKey.Client,
    ): FirstRunResult {
        val now = clock.instant()
        val decision = throttle.attemptFor(client, now)
        if (decision is ThrottleDecision.Throttled) return FirstRunResult.Throttled(decision.retryAfter)
        val existing = users.find()
        return when {
            existing !is UserAccountStoreResult.Success -> FirstRunResult.StorageFailure
            existing.value != null -> FirstRunResult.AlreadySetUp
            setupToken.isRequired() && (token == null || !setupToken.matches(token)) -> FirstRunResult.InvalidSetupToken
            else -> setUp(chosenPassword, now).also { if (it == FirstRunResult.Completed) finish(client) }
        }
    }

    private fun setUp(
        chosenPassword: String,
        now: Instant,
    ): FirstRunResult {
        val password =
            when (val check = Password.chosen(chosenPassword)) {
                is PasswordPolicyCheck.Accepted -> check.password
                is PasswordPolicyCheck.Violation -> return FirstRunResult.WeakPassword(check)
            }
        val account = UserAccount(hasher.hash(password), createdAt = now, passwordChangedAt = now)
        return transactions.inTransaction({ it == FirstRunResult.Completed }) {
            when (users.create(account)) {
                is UserAccountStoreResult.Success -> {
                    if (changelog.recordPasswordChange("Set the login password (first run)", now)) {
                        FirstRunResult.Completed
                    } else {
                        FirstRunResult.StorageFailure
                    }
                }

                UserAccountStoreResult.AlreadyExists -> {
                    FirstRunResult.AlreadySetUp
                }

                else -> {
                    FirstRunResult.StorageFailure
                }
            }
        }
    }

    private fun finish(client: ThrottleKey.Client) {
        throttle.resetFor(client)
        // A token left behind by a failed removal is harmless (first run is closed) and is removed
        // again at the next start (PrepareFirstRunUseCase).
        setupToken.discard()
    }
}
