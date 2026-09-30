// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.system.application.port.PasswordHasherPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.application.port.UserSessionsPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.PasswordChangeRequest
import io.github.scriptibus.jofi.system.domain.PasswordChangeResult
import io.github.scriptibus.jofi.system.domain.PasswordCheckResult
import io.github.scriptibus.jofi.system.domain.PasswordConfirmation
import io.github.scriptibus.jofi.system.domain.PasswordPolicyCheck
import io.github.scriptibus.jofi.system.domain.SessionRef
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import java.time.Clock
import java.time.Instant

/**
 * Changes the password of a logged-in user. The current password is required (and throttled like a
 * login, [VerifyPasswordUseCase]), so a hijacked session alone cannot take over the account; other
 * sessions end afterwards.
 */
class ChangePasswordUseCase(
    private val verifyPassword: VerifyPasswordUseCase,
    private val users: UserAccountPort,
    private val hasher: PasswordHasherPort,
    private val sessions: UserSessionsPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) {
    fun execute(request: PasswordChangeRequest): PasswordChangeResult =
        when (val check = verifyPassword.execute(PasswordConfirmation(request.currentPassword, request.client))) {
            PasswordCheckResult.Verified -> changeVerified(request)
            PasswordCheckResult.Wrong -> PasswordChangeResult.WrongCurrentPassword
            PasswordCheckResult.NotSetUp -> PasswordChangeResult.NotSetUp
            is PasswordCheckResult.Throttled -> PasswordChangeResult.Throttled(check.retryAfter)
            PasswordCheckResult.StorageFailure -> PasswordChangeResult.StorageFailure
        }

    private fun changeVerified(request: PasswordChangeRequest): PasswordChangeResult {
        val account =
            (users.find() as? UserAccountStoreResult.Success)?.value ?: return PasswordChangeResult.StorageFailure
        val changed = change(account, request.newPassword, clock.instant())
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
