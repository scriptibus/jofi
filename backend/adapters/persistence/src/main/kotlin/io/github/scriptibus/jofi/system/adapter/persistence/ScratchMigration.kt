// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.DatabaseDump
import io.github.scriptibus.jofi.system.domain.backup.DatabaseMigrationResult
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import io.github.scriptibus.jofi.system.domain.backup.TableDump
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Migrates the table dumps of an older backup (ADR-0042) in a scratch database next to the app's
 * (`jofi_restore_<id>`, dropped afterwards): Flyway creates the backup's schema version, the dumps are
 * loaded (the header must match that schema), Flyway migrates to the latest version as at every
 * upgrade, and the dumps are written again from there. The app's own database is never touched, so a
 * failure changes nothing. The database user needs the right to create databases.
 */
internal class ScratchMigration(
    private val app: DSLContext,
    private val connection: JdbcConnectionDetails,
) {
    fun migrate(backup: StagedBackup): DatabaseMigrationResult {
        val name =
            "jofi_restore_" +
                backup.id.value
                    .toString()
                    .replace("-", "")
        val url = urlOf(name) ?: return DatabaseMigrationResult.Unavailable
        return try {
            dropLeftovers()
            app.execute("CREATE DATABASE " + app.render(DSL.name(name)))
            try {
                migrateIn(url, backup)
            } finally {
                app.execute("DROP DATABASE IF EXISTS " + app.render(DSL.name(name)) + " WITH (FORCE)")
            }
        } catch (exception: Exception) {
            logger.error("Migrating a backup failed: {}", exception.javaClass.name)
            if (PostgresCopy.isDataError(exception)) {
                DatabaseMigrationResult.Refused(BackupProblem.DATA_INVALID)
            } else {
                DatabaseMigrationResult.Unavailable
            }
        }
    }

    /**
     * Drops scratch databases a crash left behind (at startup and before each migration; the backup
     * lock keeps migrations from running side by side). Only names this class makes are touched.
     */
    fun dropLeftovers(): Boolean =
        try {
            app
                .fetchValues("select datname from pg_database where datname like 'jofi\\_restore\\_%'")
                .map { it.toString() }
                .filter { SCRATCH_NAME.matches(it) }
                .forEach { app.execute("DROP DATABASE IF EXISTS " + app.render(DSL.name(it)) + " WITH (FORCE)") }
            true
        } catch (exception: Exception) {
            logger.error("Dropping leftover scratch databases failed: {}", exception.javaClass.name)
            false
        }

    private fun migrateIn(
        url: String,
        backup: StagedBackup,
    ): DatabaseMigrationResult {
        flyway(url, backup.manifest.schemaVersion.value).migrate()
        val problem = inScratch(url) { scratch -> loadProblem(scratch, backup) }
        if (problem != null) return DatabaseMigrationResult.Refused(problem)
        flyway(url, LATEST).migrate()
        return inScratch(
            url,
        ) { scratch -> DatabaseMigrationResult.Migrated(dumpAll(scratch, backup.workspace.databaseDirectory)) }
    }

    private fun <T> inScratch(
        url: String,
        work: (DSLContext) -> T,
    ): T =
        DriverManager.getConnection(url, connection.username, connection.password).use {
            work(DSL.using(it, SQLDialect.POSTGRES))
        }

    private fun loadProblem(
        scratch: DSLContext,
        backup: StagedBackup,
    ): BackupProblem? =
        when {
            tablesOf(scratch) !=
                backup.manifest.tables
                    .map { it.name }
                    .toSet()
            -> BackupProblem.TABLES_MISMATCH

            !loaded(scratch, backup) -> BackupProblem.DATA_INVALID

            else -> null
        }

    private fun tablesOf(scratch: DSLContext): Set<String> =
        scratch
            .fetchValues("select tablename from pg_tables where schemaname = 'public'")
            .map { it.toString() }
            .filterNot { it == FLYWAY_HISTORY || it in BackupTables.EXCLUDED }
            .toSet()

    // In one transaction, in the running schema's restore order (the backup's own tables are a subset
    // of those as long as no table is ever dropped; any other table comes last).
    private fun loaded(
        scratch: DSLContext,
        backup: StagedBackup,
    ): Boolean =
        scratch.transactionResult { configuration ->
            val order = BackupTables.exported.map { it.name }
            backup.manifest.tables
                .sortedBy { order.indexOf(it.name).takeIf { index -> index >= 0 } ?: order.size }
                .all { table ->
                    loadedRows(configuration.dsl(), table, backup.workspace.databaseDirectory) == table.rows
                }
        }

    private fun loadedRows(
        scratch: DSLContext,
        table: TableDump,
        directory: Path,
    ): Long = PostgresCopy.loadByName(scratch, table.name, directory.resolve("${table.name}.csv"))

    private fun dumpAll(
        scratch: DSLContext,
        directory: Path,
    ): DatabaseDump {
        Files.list(directory).use { files -> files.forEach(Files::delete) }
        val tables =
            BackupTables.exported.map {
                TableDump(it.name, PostgresCopy.dump(scratch, it, directory.resolve("${it.name}.csv")))
            }
        return DatabaseDump(PostgresCopy.schemaVersion(scratch), tables, PostgresCopy.now(scratch))
    }

    private fun flyway(
        url: String,
        target: String,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(url, connection.username, connection.password)
            .locations(MIGRATIONS)
            .target(target)
            .load()

    // The app's JDBC URL with the scratch database in place of its own.
    private fun urlOf(database: String): String? =
        JDBC_URL.matchEntire(connection.jdbcUrl)?.destructured?.let { (server, _, parameters) ->
            "$server$database$parameters"
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ScratchMigration::class.java)
        val SCRATCH_NAME = Regex("jofi_restore_[0-9a-f]{32}")
        val JDBC_URL = Regex("""(jdbc:postgresql://[^/]*/)([^?]*)(.*)""")
        const val MIGRATIONS = "classpath:db/migration"
        const val LATEST = "latest"
        const val FLYWAY_HISTORY = "flyway_schema_history"
    }
}
