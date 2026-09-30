// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.application.port.DatabaseBackupPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyBackupPort
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.InterruptedRestore
import io.github.scriptibus.jofi.system.domain.backup.RestoreRecoveryResult

/**
 * Finishes restores that began replacing files or the keyset but did not end (ADR-0042): after a
 * failed step, a failed commit, and at every start of `app` before the master keyset is checked. The
 * restore's changelog entry is the commit marker: written in the restore's transaction, it exists
 * exactly when the database was replaced. With it the restore rolls forward (leftovers go); without
 * it the previous files and keyset go back. Also drops scratch databases of crashed migrations.
 */
class RecoverRestoreUseCase(
    private val archive: BackupArchivePort,
    private val masterKey: MasterKeyBackupPort,
    private val changelog: ChangelogPort,
    private val database: DatabaseBackupPort,
) {
    /** Recovers restore [only], or every interrupted restore when it is `null`. */
    fun execute(only: BackupId? = null): RestoreRecoveryResult {
        val scratchDropped = only != null || database.dropScratchDatabases()
        val interrupted = archive.interruptedRestores() ?: return RestoreRecoveryResult.FAILED
        val results = interrupted.filter { only == null || it.id == only }.map(::recover)
        return when {
            !scratchDropped || RestoreRecoveryResult.FAILED in results -> RestoreRecoveryResult.FAILED
            RestoreRecoveryResult.ROLLED_BACK in results -> RestoreRecoveryResult.ROLLED_BACK
            results.isNotEmpty() -> RestoreRecoveryResult.ROLLED_FORWARD
            else -> RestoreRecoveryResult.NOTHING_TO_DO
        }
    }

    private fun recover(restore: InterruptedRestore): RestoreRecoveryResult =
        when (val committed = changelog.listByEntity(BackupRestore.entityOf(restore.id), ChangelogLimit(1))) {
            is ChangelogResult.Success if committed.value.isNotEmpty() -> {
                archive.discard(restore.id)
                RestoreRecoveryResult.ROLLED_FORWARD
            }

            is ChangelogResult.Success -> {
                rollBack(restore)
            }

            else -> {
                RestoreRecoveryResult.FAILED
            }
        }

    private fun rollBack(restore: InterruptedRestore): RestoreRecoveryResult {
        val filesBack = archive.revertFiles(restore.id)
        val keysetBack = restore.previousKeyset?.let(masterKey::reinstate) ?: true
        return if (filesBack && keysetBack) {
            archive.endRestore(restore.id)
            RestoreRecoveryResult.ROLLED_BACK
        } else {
            RestoreRecoveryResult.FAILED
        }
    }
}
