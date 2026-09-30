// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain.backup

import java.math.BigInteger
import java.time.Instant

/**
 * What a backup holds (`manifest.json`, ADR-0042): format version, the Jofi version and database
 * schema version (latest Flyway migration) it was made with, when, the table dumps with their row
 * counts, and every other entry with size and SHA-256.
 */
data class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val schemaVersion: SchemaVersion,
    val createdAt: Instant,
    val tables: List<TableDump>,
    val entries: List<BackupEntry>,
) {
    init {
        require(APP_VERSION.matches(appVersion)) { "Not an app version" }
    }

    val includesKeyset: Boolean get() = entries.any { it.path == BackupPath.KEYSET }
    val dataFileCount: Int get() = entries.count { it.path.isDataFile }
    val rowCount: Long get() = tables.sumOf { it.rows }

    /** Made with an older schema: its table dumps must be migrated before they can be restored. */
    fun needsMigrationTo(running: RunningSchema): Boolean = schemaVersion < running.version

    /**
     * The first reason this backup cannot replace the data of an instance running [running], given
     * what the archive really held ([found], every entry but the manifest); `null` when it can. The
     * tables of an older backup are those of its own schema; migrating them checks them.
     */
    fun problemWith(
        found: Map<BackupPath, EntryDigest>,
        running: RunningSchema,
    ): BackupProblem? =
        when {
            formatVersion != FORMAT_VERSION -> BackupProblem.UNSUPPORTED_FORMAT
            schemaVersion > running.version -> BackupProblem.SCHEMA_NEWER
            !matches(found) -> BackupProblem.CONTENT_MISMATCH
            tables.any { it.path !in found } -> BackupProblem.TABLES_MISMATCH
            !needsMigrationTo(running) && !hasTablesOf(running) -> BackupProblem.TABLES_MISMATCH
            else -> contentProblem()
        }

    private fun contentProblem(): BackupProblem? =
        when {
            needsKeyset() && !includesKeyset -> BackupProblem.KEYSET_MISSING
            tables.singleOrNull { it.name == ACCOUNT_TABLE }?.rows != 1L -> BackupProblem.ACCOUNT_MISSING
            else -> null
        }

    /** This backup after its table dumps were migrated to [schema]: [tables] are the migrated dumps. */
    fun migratedTo(
        schema: SchemaVersion,
        tables: List<TableDump>,
    ): BackupManifest = copy(schemaVersion = schema, tables = tables)

    private fun hasTablesOf(running: RunningSchema): Boolean =
        tables.size == running.tables.size && tables.map { it.name }.toSet() == running.tables.toSet()

    private fun matches(found: Map<BackupPath, EntryDigest>): Boolean =
        entries.map { it.path }.toSet().size == entries.size && entries.associate { it.path to it.digest } == found

    // Secrets (and the check value of their key) are useless without the keyset they were encrypted with.
    private fun needsKeyset(): Boolean = tables.any { it.name in KEYSET_TABLES && it.rows > 0 }

    companion object {
        /** The archive layout this version writes and reads. */
        const val FORMAT_VERSION = 1

        /** Names the format inside the manifest, so a stray zip is recognised as not a backup. */
        const val FORMAT_NAME = "jofi-backup"

        /** The single user; a backup without exactly one account would lock everyone out. */
        const val ACCOUNT_TABLE = "user_account"

        private val APP_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")

        /** Tables that are only readable with the master keyset. */
        val KEYSET_TABLES: Set<String> = setOf("secret", "master_key_check")
    }
}

/** One file in the archive. */
data class BackupEntry(
    val path: BackupPath,
    val digest: EntryDigest,
)

/** Size in bytes and SHA-256 (lowercase hex) of an entry's content. */
data class EntryDigest(
    val size: Long,
    val sha256: String,
) {
    init {
        require(size >= 0) { "A size cannot be negative" }
        require(SHA256.matches(sha256)) { "Not a lowercase hex SHA-256" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** The CSV dump of one table (`database/<name>.csv`) and how many rows it holds. */
data class TableDump(
    val name: String,
    val rows: Long,
) {
    val path: BackupPath get() = BackupPath.table(name)

    init {
        require(rows >= 0) { "A row count cannot be negative" }
    }
}

/** The schema an instance runs: its latest migration and the tables a backup holds, in restore order. */
data class RunningSchema(
    val version: SchemaVersion,
    val tables: List<String>,
)

/** A Flyway migration version such as `20260930064000` or `1.2`, compared part by part as numbers. */
@JvmInline
value class SchemaVersion(
    val value: String,
) : Comparable<SchemaVersion> {
    init {
        require(value.length <= MAX_LENGTH && PATTERN.matches(value)) { "Not a migration version" }
    }

    override fun compareTo(other: SchemaVersion): Int {
        val mine = parts()
        val theirs = other.parts()
        for (index in 0 until maxOf(mine.size, theirs.size)) {
            val compared =
                mine
                    .getOrElse(
                        index,
                    ) { BigInteger.ZERO }
                    .compareTo(theirs.getOrElse(index) { BigInteger.ZERO })
            if (compared != 0) return compared
        }
        return 0
    }

    override fun toString(): String = value

    private fun parts(): List<BigInteger> = value.split('.', '_').map(::BigInteger)

    private companion object {
        const val MAX_LENGTH = 32
        val PATTERN = Regex("""\d+([._]\d+)*""")
    }
}
