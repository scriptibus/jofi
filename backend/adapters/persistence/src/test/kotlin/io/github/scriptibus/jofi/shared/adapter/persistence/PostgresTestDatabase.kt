// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One real PostgreSQL (the pinned pgvector image from `gradle.properties`) per test JVM, migrated
 * by Flyway from zero with the production migrations on the classpath.
 */
object PostgresTestDatabase {
    val container: PostgreSQLContainer by lazy {
        val image = requireNotNull(System.getProperty("jofi.postgresImage")) { "jofi.postgresImage is not set" }
        PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres")).apply { start() }
    }

    /** Runs every migration on a fresh schema and returns a jOOQ context for it. */
    fun migratedFromZero(): DSLContext {
        val flyway =
            Flyway
                .configure()
                .dataSource(container.jdbcUrl, container.username, container.password)
                .cleanDisabled(false)
                .load()
        flyway.clean()
        flyway.migrate()
        return dsl
    }

    private val dsl: DSLContext by lazy {
        DSL.using(container.jdbcUrl, container.username, container.password)
    }
}
