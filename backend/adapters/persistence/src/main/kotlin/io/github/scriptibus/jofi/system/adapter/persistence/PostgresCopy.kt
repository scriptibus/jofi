// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.system.domain.backup.SchemaVersion
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.postgresql.PGConnection
import org.postgresql.copy.CopyManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.SQLException
import java.time.Instant

/**
 * Table dumps with PostgreSQL's `COPY` in CSV with a header row (ADR-0042), streamed between the
 * database of [DSLContext] (on the connection of its current transaction) and files.
 */
internal object PostgresCopy {
    /** Writes [table] to [file] (replacing it); the number of rows. */
    fun dump(
        dsl: DSLContext,
        table: Table<*>,
        file: Path,
    ): Long =
        Files
            .newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            .use { output ->
                copy(dsl) { it.copyOut("COPY ${columns(dsl, table)} TO STDOUT (FORMAT csv, HEADER true)", output) }
            }

    /** Loads [file] into [table]; its header must name exactly the table's columns. */
    fun load(
        dsl: DSLContext,
        table: Table<*>,
        file: Path,
    ): Long = copyIn(dsl, columns(dsl, table), file)

    /** Loads [file] into the table named [name], with all its columns in catalog order. */
    fun loadByName(
        dsl: DSLContext,
        name: String,
        file: Path,
    ): Long = copyIn(dsl, dsl.render(DSL.name(name)), file)

    // Rows keep their ids, so identity columns continue after the highest restored one.
    fun continueIdentity(
        dsl: DSLContext,
        table: Table<*>,
    ) {
        val column = table.identity?.field ?: return
        dsl.execute(
            "select setval(pg_get_serial_sequence({0}, {1}), coalesce((select max({2}) from {3}), 0) + 1, false)",
            DSL.inline(dsl.render(table)),
            DSL.inline(column.name),
            DSL.field(DSL.name(column.name)),
            table,
        )
    }

    /** The database's clock: in a transaction, the time it started (a snapshot's time). */
    fun now(dsl: DSLContext): Instant =
        checkNotNull(dsl.select(DSL.currentOffsetDateTime()).fetchOne()?.value1()).toInstant()

    /** The latest applied Flyway migration. */
    fun schemaVersion(dsl: DSLContext): SchemaVersion =
        SchemaVersion(
            checkNotNull(
                dsl.fetchValue(
                    "select version from flyway_schema_history where success and version is not null " +
                        "order by installed_rank desc limit 1",
                ),
            ).toString(),
        )

    /** SQLSTATE classes 22 (data exception) and 23 (integrity constraint violation): the dump is wrong. */
    fun isDataError(exception: Throwable): Boolean =
        generateSequence(exception) { it.cause }
            .filterIsInstance<SQLException>()
            .any { it.sqlState?.take(2) in DATA_ERROR_CLASSES }

    private fun copyIn(
        dsl: DSLContext,
        target: String,
        file: Path,
    ): Long =
        Files.newInputStream(file).use { input ->
            copy(dsl) { it.copyIn("COPY $target FROM STDIN (FORMAT csv, HEADER match)", input) }
        }

    private fun columns(
        dsl: DSLContext,
        table: Table<*>,
    ): String =
        dsl.render(table) + table.fields().joinToString(prefix = " (", postfix = ")") { dsl.render(DSL.name(it.name)) }

    private fun copy(
        dsl: DSLContext,
        action: (CopyManager) -> Long,
    ): Long = checkNotNull(dsl.connectionResult { action(it.unwrap(PGConnection::class.java).copyAPI) })

    private val DATA_ERROR_CLASSES = setOf("22", "23")
}
