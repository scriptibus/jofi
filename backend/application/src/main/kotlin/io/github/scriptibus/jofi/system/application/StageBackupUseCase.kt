// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.BackupArchivePort
import io.github.scriptibus.jofi.system.application.port.BackupLockPort
import io.github.scriptibus.jofi.system.application.port.DatabaseBackupPort
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.BackupStageResult
import io.github.scriptibus.jofi.system.domain.backup.BackupUnpackResult
import io.github.scriptibus.jofi.system.domain.backup.DatabaseMigrationResult
import io.github.scriptibus.jofi.system.domain.backup.RunningSchema
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import java.io.InputStream

/**
 * The first half of a restore (ADR-0042): unpacks an uploaded backup under size, entry and path
 * limits and checks format, schema version, tables, checksums, account and keyset, all before any
 * data is touched. A backup of an older schema is migrated to the running one in a scratch database.
 * What passes waits for the user's confirmation ([RestoreBackupUseCase]). Runs alone (backup lock).
 */
class StageBackupUseCase(
    private val archive: BackupArchivePort,
    private val database: DatabaseBackupPort,
    private val lock: BackupLockPort,
) {
    fun execute(upload: InputStream): BackupStageResult = lock.exclusive { stage(upload) } ?: BackupStageResult.Busy

    private fun stage(upload: InputStream): BackupStageResult {
        val running = database.runningSchema() as? SystemStoreResult.Success ?: return BackupStageResult.StorageFailure
        return when (val unpacked = archive.unpack(upload)) {
            is BackupUnpackResult.Refused -> BackupStageResult.Refused(unpacked.problem)
            BackupUnpackResult.StorageFailure -> BackupStageResult.StorageFailure
            is BackupUnpackResult.Unpacked -> checked(unpacked, running.value)
        }
    }

    private fun checked(
        unpacked: BackupUnpackResult.Unpacked,
        running: RunningSchema,
    ): BackupStageResult {
        val backup = unpacked.backup
        val problem = backup.manifest.problemWith(unpacked.found, running)
        val result =
            when {
                problem != null -> BackupStageResult.Refused(problem)
                backup.manifest.needsMigrationTo(running) -> migrated(backup)
                else -> BackupStageResult.Staged(backup)
            }
        if (result !is BackupStageResult.Staged) archive.discard(backup.id)
        return result
    }

    private fun migrated(backup: StagedBackup): BackupStageResult =
        when (val migration = database.migrate(backup)) {
            is DatabaseMigrationResult.Migrated -> {
                val manifest = backup.manifest.migratedTo(migration.dump.schemaVersion, migration.dump.tables)
                val migrated = backup.copy(manifest = manifest, migratedFrom = backup.manifest.schemaVersion)
                archive.keep(migrated)
                BackupStageResult.Staged(migrated)
            }

            is DatabaseMigrationResult.Refused -> {
                BackupStageResult.Refused(migration.problem)
            }

            DatabaseMigrationResult.Unavailable -> {
                BackupStageResult.Refused(BackupProblem.SCHEMA_OLDER)
            }
        }
}
