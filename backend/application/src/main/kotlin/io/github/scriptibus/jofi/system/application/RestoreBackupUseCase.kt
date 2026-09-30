// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.application.port.BackupLockPort
import io.github.scriptibus.jofi.system.domain.PasswordCheckResult
import io.github.scriptibus.jofi.system.domain.PasswordConfirmation
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.BackupRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.RestoreRecoveryResult

/**
 * The second half of a restore (ADR-0042): replaces all data with a staged backup. It needs the
 * current password (throttled like a login) and the server-enforced two-step confirmation
 * (ADR-0039), runs alone (backup lock), and puts everything in place in one transaction with its
 * changelog entry. Every outcome but a commit, a failed commit included, goes to
 * [RecoverRestoreUseCase], which puts the previous files and keyset back. Every login session ends,
 * so the user logs in again with the backup's password.
 */
class RestoreBackupUseCase(
    private val archive: BackupArchivePort,
    private val install: InstallBackupUseCase,
    private val recover: RecoverRestoreUseCase,
    private val confirmAction: ConfirmActionUseCase,
    private val transactions: TransactionPort,
    private val verifyPassword: VerifyPasswordUseCase,
    private val lock: BackupLockPort,
) {
    fun execute(
        id: BackupId,
        requester: ConfirmationRequester,
        confirmation: PasswordConfirmation,
        token: ConfirmationToken?,
    ): BackupRestoreResult {
        val check = verifyPassword.execute(confirmation)
        if (check != PasswordCheckResult.Verified) return BackupRestoreResult.PasswordRefused(check)
        return lock.exclusive { restore(id, requester, token) } ?: BackupRestoreResult.Busy
    }

    private fun restore(
        id: BackupId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): BackupRestoreResult {
        var result: BackupRestoreResult? = null
        var recovered = RestoreRecoveryResult.NOTHING_TO_DO
        try {
            result =
                transactions.inTransaction(
                    { it is BackupRestoreResult.Restored },
                ) { confirmedInstall(id, requester, token) }
        } finally {
            // Also when the commit threw: the changelog entry then tells whether it happened.
            if (result is BackupRestoreResult.Restored) archive.discard(id) else recovered = recover.execute(id)
        }
        return if (recovered == RestoreRecoveryResult.FAILED) BackupRestoreResult.Inconsistent else checkNotNull(result)
    }

    private fun confirmedInstall(
        id: BackupId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): BackupRestoreResult {
        val backup = archive.find(id) ?: return BackupRestoreResult.NotFound
        val request = ConfirmationRequest(requester, BackupRestore.action(backup), token)
        return when (val confirmation = confirmAction.execute(request)) {
            is ConfirmationResult.Confirmed -> install.execute(backup, confirmation, requester.actor)
            is ConfirmationResult.Unconfirmed -> BackupRestoreResult.NotConfirmed(confirmation)
        }
    }
}
