// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.PasswordHasherPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.application.port.UserSessionsPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.PasswordChangeRequest
import io.github.scriptibus.jofi.system.domain.PasswordChangeResult
import io.github.scriptibus.jofi.system.domain.PasswordPolicyCheck
import io.github.scriptibus.jofi.system.domain.SessionRef
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import java.time.Clock
import java.time.Instant

/**
 * Changes the password of a logged-in user. The current password is required (and throttled like a
 * login), so a hijacked session alone cannot take over the account; other sessions end afterwards.
 */
class ChangePasswordUseCase(
    private val users: UserAccountPort,
    private val hasher: PasswordHasherPort,
    private val throttle: LoginThrottlePort,
    private val sessions: UserSessionsPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) {
    fun execute(request: PasswordChangeRequest): PasswordChangeResult {
        val now = clock.instant()
        val decision = throttle.attemptFor(request.client, now)
        if (decision is ThrottleDecision.Throttled) return PasswordChangeResult.Throttled(decision.retryAfter)
        return when (val found = users.find()) {
            is UserAccountStoreResult.Success -> {
                found.value?.let { verifyAndChange(it, request, now) } ?: PasswordChangeResult.NotSetUp
            }

            else -> {
                PasswordChangeResult.StorageFailure
            }
        }
    }

    private fun verifyAndChange(
        account: UserAccount,
        request: PasswordChangeRequest,
        now: Instant,
    ): PasswordChangeResult {
        val current = Password.submitted(request.currentPassword)
        if (current == null || !hasher.matches(current, account.passwordHash)) {
            return PasswordChangeResult.WrongCurrentPassword
        }
        throttle.resetFor(request.client)
        val changed = change(account, request.newPassword, now)
        return if (changed == PasswordChangeResult.Changed) endOtherSessions(request.session) else changed
    }

    // The change protects against a stolen session, so a session that survives it is not a success.
    private fun endOtherSessions(keep: SessionRef): PasswordChangeResult =
        when (sessions.endAllExcept(keep)) {
            AuthSideEffectResult.Success -> PasswordChangeResult.Changed
            AuthSideEffectResult.Failure -> PasswordChangeResult.ChangedButOtherSessionsRemain
        }

    private fun change(
        account: UserAccount,
        newPassword: String,
        now: Instant,
    ): PasswordChangeResult {
        val password =
            when (val check = Password.chosen(newPassword)) {
                is PasswordPolicyCheck.Accepted -> check.password
                is PasswordPolicyCheck.Violation -> return PasswordChangeResult.WeakPassword(check)
            }
        val changed = account.withPassword(hasher.hash(password), now)
        return transactions.inTransaction({ it == PasswordChangeResult.Changed }) {
            when {
                users.update(changed) !is UserAccountStoreResult.Success -> PasswordChangeResult.StorageFailure

                !changelog.recordPasswordChange(
                    "Changed the login password",
                    now,
                ) -> PasswordChangeResult.StorageFailure

                else -> PasswordChangeResult.Changed
            }
        }
    }
}
