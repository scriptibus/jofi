// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.application.port.DatabaseBackupPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyBackupPort
import io.github.scriptibus.jofi.system.application.port.MasterKeyRecordPort
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.BackupRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.DatabaseRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import java.time.Clock

/**
 * Puts a confirmed backup in place, inside the caller's transaction (only [RestoreBackupUseCase]
 * calls it, with the gate's [ConfirmationResult.Confirmed]). First the tables and the changelog
 * entry, which is also the commit marker ([RecoverRestoreUseCase]), then the checks; then, after the
 * work directory is marked as a restore in progress with a copy of the current keyset, the data
 * volume's files and, at the very end, the keyset. It never undoes anything itself: the caller hands
 * every outcome but a commit to [RecoverRestoreUseCase].
 */
class InstallBackupUseCase(
    private val archive: BackupArchivePort,
    private val database: DatabaseBackupPort,
    private val masterKey: MasterKeyBackupPort,
    private val keyRecords: MasterKeyRecordPort,
    private val changelog: ChangelogPort,
    private val clock: Clock,
) {
    fun execute(
        backup: StagedBackup,
        proof: ConfirmationResult.Confirmed,
        actor: Actor,
    ): BackupRestoreResult =
        tablesFailure(backup, proof)
            ?: keysetFailure(backup)
            ?: changelogFailure(backup, actor)
            ?: beginFailure(backup)
            ?: installFiles(backup, proof)

    private fun tablesFailure(
        backup: StagedBackup,
        proof: ConfirmationResult.Confirmed,
    ): BackupRestoreResult? =
        when (database.replaceAll(backup, proof)) {
            DatabaseRestoreResult.Restored -> null
            DatabaseRestoreResult.DataInvalid -> BackupRestoreResult.Refused(BackupProblem.DATA_INVALID)
            DatabaseRestoreResult.StorageFailure -> BackupRestoreResult.StorageFailure
        }

    // The restored check value must come from the backup's keyset, or the next start refuses (ADR-0035).
    private fun keysetFailure(backup: StagedBackup): BackupRestoreResult? {
        val recorded = keyRecords.findCheckValue() as? SystemStoreResult.Success
        val checkValue = recorded?.value
        val keyset = backup.keyset
        return when {
            recorded == null -> BackupRestoreResult.StorageFailure

            checkValue == null -> null

            keyset == null ||
                !masterKey.verifies(
                    keyset,
                    checkValue,
                )
            -> BackupRestoreResult.Refused(BackupProblem.KEYSET_MISMATCH)

            else -> null
        }
    }

    private fun changelogFailure(
        backup: StagedBackup,
        actor: Actor,
    ): BackupRestoreResult? {
        val manifest = backup.manifest
        val description =
            "Restored the backup of ${manifest.createdAt} (Jofi ${manifest.appVersion}, " +
                "${manifest.rowCount} rows, ${manifest.dataFileCount} files)"
        val entry =
            ChangelogEntry(BackupRestore.entityOf(backup.id), actor, clock.instant(), ChangeSummary(description))
        return if (changelog.append(entry) is ChangelogResult.Success) null else BackupRestoreResult.StorageFailure
    }

    private fun beginFailure(backup: StagedBackup): BackupRestoreResult? {
        val current = masterKey.copy() as? SystemStoreResult.Success ?: return BackupRestoreResult.StorageFailure
        return if (archive.beginRestore(backup, current.value)) null else BackupRestoreResult.StorageFailure
    }

    private fun installFiles(
        backup: StagedBackup,
        proof: ConfirmationResult.Confirmed,
    ): BackupRestoreResult =
        when {
            !archive.installFiles(backup, proof) -> BackupRestoreResult.StorageFailure
            backup.keyset != null && !masterKey.install(backup, proof) -> BackupRestoreResult.StorageFailure
            else -> BackupRestoreResult.Restored(backup.manifest)
        }
}
