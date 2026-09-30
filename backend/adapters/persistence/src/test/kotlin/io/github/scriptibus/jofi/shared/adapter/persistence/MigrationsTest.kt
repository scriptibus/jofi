// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Public
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.jooq.DSLContext
import org.jooq.Table
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/** Flyway from zero on a real PostgreSQL, and the generated jOOQ code matches the migrated schema. */
class MigrationsTest {
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
    }

    @Test
    fun `migrations enable pgvector and pg_trgm`() {
        dsl.fetchValues("select extname from pg_extension").map { it.toString() } shouldContainAll
            listOf("vector", "pg_trgm")
        dsl.fetchValue("select '[1,2,3]'::vector <-> '[1,2,3]'::vector") shouldBe 0.0
        dsl.fetchValue("select similarity('Jofi', 'Jofi')") shouldBe 1.0f
    }

    @Test
    fun `generated jOOQ code matches the migrated schema`() {
        val migrated =
            dsl
                .meta()
                .getSchemas("public")
                .single()
                .tables
                .filterNot { it.name == "flyway_schema_history" }

        describe(Public.PUBLIC.tables) shouldBe describe(migrated)
    }

    @Test
    fun `migration versions are timestamps`() {
        val names = File(MIGRATIONS).listFiles().orEmpty().map { it.name }

        names.isNotEmpty() shouldBe true
        names.forEach { it shouldMatch Regex("""V\d{14}__[a-z0-9_]+\.sql""") }
    }

    private fun describe(tables: List<Table<*>>): Map<String, List<String>> =
        tables.associate { table ->
            table.name to table.fields().map { "${it.name}:${it.dataType.typeName}:${it.dataType.nullable()}" }
        }

    private companion object {
        const val MIGRATIONS = "src/main/resources/db/migration"
    }
}
