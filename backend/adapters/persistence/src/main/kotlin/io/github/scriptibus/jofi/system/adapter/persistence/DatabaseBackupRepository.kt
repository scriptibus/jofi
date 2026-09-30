// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.application.port.DatabaseBackupPort
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.DatabaseDump
import io.github.scriptibus.jofi.system.domain.backup.DatabaseMigrationResult
import io.github.scriptibus.jofi.system.domain.backup.DatabaseRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.RunningSchema
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import io.github.scriptibus.jofi.system.domain.backup.TableDump
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path

/**
 * Dumps and restores the backed-up tables ([BackupTables]) with PostgreSQL's `COPY` in CSV with a
 * header row ([PostgresCopy], ADR-0042): streamed between database and files, exact for every column
 * type, and the header is matched against the table's columns on restore. Older backups are migrated
 * in a scratch database first ([ScratchMigration]). Nothing is logged but the operation and the
 * exception type; rows are personal data.
 */
@Component
class DatabaseBackupRepository(
    private val dsl: DSLContext,
    transactionManager: PlatformTransactionManager,
    connection: JdbcConnectionDetails,
) : DatabaseBackupPort {
    // One snapshot for every table, so the dump is consistent even while the app keeps writing.
    private val snapshot =
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            isReadOnly = true
        }
    private val scratch = ScratchMigration(dsl, connection)

    override fun runningSchema(): SystemStoreResult<RunningSchema> =
        guarded("read schema") { RunningSchema(PostgresCopy.schemaVersion(dsl), BackupTables.exported.map { it.name }) }

    override fun dump(directory: Path): SystemStoreResult<DatabaseDump> =
        guarded("dump") {
            checkNotNull(
                snapshot.execute { _ ->
                    val tables =
                        BackupTables.exported.map {
                            TableDump(
                                it.name,
                                PostgresCopy.dump(dsl, it, directory.resolve("${it.name}.csv")),
                            )
                        }
                    DatabaseDump(PostgresCopy.schemaVersion(dsl), tables, PostgresCopy.now(dsl))
                },
            )
        }

    override fun migrate(backup: StagedBackup): DatabaseMigrationResult = scratch.migrate(backup)

    override fun dropScratchDatabases(): Boolean = scratch.dropLeftovers()

    override fun replaceAll(
        backup: StagedBackup,
        proof: ConfirmationResult.Confirmed,
    ): DatabaseRestoreResult =
        if (!BackupRestore.confirms(proof, backup)) {
            DatabaseRestoreResult.StorageFailure
        } else {
            try {
                restore(backup)
            } catch (exception: Exception) {
                logger.error("Restoring the database failed: {}", exception.javaClass.name)
                if (PostgresCopy.isDataError(
                        exception,
                    )
                ) {
                    DatabaseRestoreResult.DataInvalid
                } else {
                    DatabaseRestoreResult.StorageFailure
                }
            }
        }

    private fun restore(backup: StagedBackup): DatabaseRestoreResult {
        val expected = backup.manifest.tables.associate { it.name to it.rows }
        val cleared = BackupTables.exported + BackupTables.CLEARED_ON_RESTORE
        dsl.execute("TRUNCATE " + cleared.joinToString { dsl.render(it) })
        val directory = backup.workspace.databaseDirectory
        val loaded =
            BackupTables.exported.associate {
                it.name to
                    PostgresCopy.load(dsl, it, directory.resolve("${it.name}.csv"))
            }
        BackupTables.exported.forEach { PostgresCopy.continueIdentity(dsl, it) }
        return if (loaded == expected) DatabaseRestoreResult.Restored else DatabaseRestoreResult.DataInvalid
    }

    private fun <T> guarded(
        operation: String,
        work: () -> T,
    ): SystemStoreResult<T> =
        try {
            SystemStoreResult.Success(work())
        } catch (exception: Exception) {
            logger.error("Backup {} failed: {}", operation, exception.javaClass.name)
            SystemStoreResult.StorageFailure(operation)
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(DatabaseBackupRepository::class.java)
    }
}
