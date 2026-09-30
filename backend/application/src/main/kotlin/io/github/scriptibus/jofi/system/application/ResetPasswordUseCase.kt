// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.system.application.port.PasswordResetMarkerPort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.application.port.UserSessionsPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.PasswordResetResult
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import java.time.Clock

/**
 * Password recovery (`JOFI_RESET_PASSWORD=true` at startup): whoever controls the server may start
 * over. Deletes the account, ends every session and replaces the setup token, so the next first run
 * needs access to the data volume again. Stored data and secrets stay. A reset happens once per
 * setting of the flag: while it stays set, later starts change nothing ([PasswordResetResult.AlreadyApplied]).
 */
class ResetPasswordUseCase(
    private val users: UserAccountPort,
    private val sessions: UserSessionsPort,
    private val setupToken: SetupTokenPort,
    private val marker: PasswordResetMarkerPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) {
    /** [requested]: whether `JOFI_RESET_PASSWORD` is set on this start. */
    fun execute(requested: Boolean): PasswordResetResult =
        when {
            !requested -> PasswordResetResult.NotRequested.also { marker.clear() }
            marker.isSet() || setupToken.isIssued() -> PasswordResetResult.AlreadyApplied
            else -> reset().also { if (it != PasswordResetResult.StorageFailure) marker.set() }
        }

    private fun reset(): PasswordResetResult {
        val entry =
            ChangelogEntry(
                entity = UserAccount.ENTITY,
                actor = Actor.System(ACTOR),
                occurredAt = clock.instant(),
                change = ChangeSummary("Reset the login password (JOFI_RESET_PASSWORD)"),
            )
        val deleted =
            transactions.inTransaction({ it == PasswordResetResult.Reset }) {
                when (users.delete()) {
                    is UserAccountStoreResult.Success -> recorded(changelog.append(entry))
                    UserAccountStoreResult.NotFound -> PasswordResetResult.NothingToReset
                    else -> PasswordResetResult.StorageFailure
                }
            }
        return if (deleted == PasswordResetResult.Reset) afterDeletion() else deleted
    }

    private fun recorded(result: ChangelogResult<Unit>): PasswordResetResult =
        if (result is ChangelogResult.Success) PasswordResetResult.Reset else PasswordResetResult.StorageFailure

    // Sessions also end on their next request (they belong to the deleted account); ending them
    // here as well removes them from the database right away. An old token file is replaced, so a
    // token read before the reset is worthless.
    private fun afterDeletion(): PasswordResetResult {
        val ended = sessions.endAll()
        val discarded = setupToken.discard()
        val issued = setupToken.issue()
        val complete = listOf(ended, discarded, issued).all { it == AuthSideEffectResult.Success }
        return if (complete) PasswordResetResult.Reset else PasswordResetResult.ResetWithFailures
    }

    private companion object {
        const val ACTOR = "password-reset"
    }
}
