// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.backup.DatabaseDump
import io.github.scriptibus.jofi.system.domain.backup.DatabaseMigrationResult
import io.github.scriptibus.jofi.system.domain.backup.DatabaseRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.RunningSchema
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import java.nio.file.Path

/**
 * The database side of backups (ADR-0042): which tables a backup holds, their dumps and their
 * replacement. Implementations never throw.
 */
interface DatabaseBackupPort {
    /** The latest applied migration and the tables a backup holds, in restore order. */
    fun runningSchema(): SystemStoreResult<RunningSchema>

    /** Writes one CSV dump per backed-up table into [directory], all from one consistent snapshot. */
    fun dump(directory: Path): SystemStoreResult<DatabaseDump>

    /**
     * Migrates the table dumps of an older [backup] to the running schema, in place: they are loaded
     * into a scratch database at the backup's schema version, Flyway migrates it, and the dumps are
     * written again from there. The live data is never touched.
     */
    fun migrate(backup: StagedBackup): DatabaseMigrationResult

    /** Drops scratch databases a crashed migration left behind; `false` when that failed. */
    fun dropScratchDatabases(): Boolean

    /**
     * Replaces every backed-up table with the backup's dumps and ends every login session, in the
     * caller's transaction, so a failure anywhere later rolls it back.
     */
    fun replaceAll(
        backup: StagedBackup,
        proof: ConfirmationResult.Confirmed,
    ): DatabaseRestoreResult
}
