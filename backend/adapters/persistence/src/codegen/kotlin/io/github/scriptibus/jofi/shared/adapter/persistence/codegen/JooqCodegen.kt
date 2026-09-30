// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence.codegen

import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Logging
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File

/** Package of the generated jOOQ code; follows `<base>.<context>.adapter.<kind>`. */
const val GENERATED_PACKAGE = "io.github.scriptibus.jofi.shared.adapter.persistence.jooq"

/**
 * Build-time jOOQ code generation (spec 4.5, ADR-0009): runs every Flyway migration from zero on
 * a throwaway PostgreSQL container, then generates jOOQ code from the resulting schema. Called by
 * the `generateJooq` Gradle task with: migrations directory, output directory, container image.
 */
fun main(args: Array<String>) {
    require(args.size == EXPECTED_ARGUMENTS) { "Usage: <migrations dir> <output dir> <postgres image>" }
    val (migrations, output, image) = args
    val imageName = DockerImageName.parse(image).asCompatibleSubstituteFor("postgres")
    PostgreSQLContainer(imageName).use { postgres ->
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("filesystem:$migrations")
            .load()
            .migrate()
        File(output).deleteRecursively()
        GenerationTool.generate(configuration(postgres, output))
    }
}

private const val EXPECTED_ARGUMENTS = 3

private fun configuration(
    postgres: PostgreSQLContainer,
    output: String,
): Configuration =
    Configuration()
        .withLogging(Logging.WARN)
        .withJdbc(
            Jdbc()
                .withDriver(postgres.driverClassName)
                .withUrl(postgres.jdbcUrl)
                .withUser(postgres.username)
                .withPassword(postgres.password),
        ).withGenerator(
            Generator()
                .withDatabase(
                    Database()
                        .withName("org.jooq.meta.postgres.PostgresDatabase")
                        .withInputSchema("public")
                        .withExcludes("flyway_schema_history")
                        // Extension functions (pgvector, pg_trgm) and trigger functions are not
                        // called through generated code.
                        .withIncludeRoutines(false),
                ).withGenerate(
                    Generate()
                        .withGeneratedAnnotation(false)
                        .withJavaTimeTypes(true),
                ).withTarget(Target().withPackageName(GENERATED_PACKAGE).withDirectory(output)),
        )
