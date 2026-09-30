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

/** An empty PostgreSQL database to migrate and read the schema from. */
data class CodegenDatabase(
    val url: String,
    val user: String,
    val password: String,
)

/**
 * Build-time jOOQ code generation (spec 4.5, ADR-0009, ADR-0030): runs every Flyway migration from
 * zero on an empty PostgreSQL, then generates jOOQ code from the resulting schema. Called by the
 * `generateJooq` Gradle task with: migrations directory, output directory, container image.
 *
 * The database is a throwaway Testcontainers PostgreSQL, unless `JOFI_CODEGEN_JDBC_URL` (plus
 * `JOFI_CODEGEN_JDBC_USER` and `JOFI_CODEGEN_JDBC_PASSWORD`) names one: the container image build has
 * no Docker daemon and starts the same pinned image's PostgreSQL itself.
 */
fun main(args: Array<String>) {
    require(args.size == EXPECTED_ARGUMENTS) { "Usage: <migrations dir> <output dir> <postgres image>" }
    val (migrations, output, image) = args
    val external = System.getenv("JOFI_CODEGEN_JDBC_URL")
    if (external.isNullOrBlank()) {
        val imageName = DockerImageName.parse(image).asCompatibleSubstituteFor("postgres")
        PostgreSQLContainer(imageName).use { postgres ->
            postgres.start()
            generate(CodegenDatabase(postgres.jdbcUrl, postgres.username, postgres.password), migrations, output)
        }
    } else {
        val user = System.getenv("JOFI_CODEGEN_JDBC_USER").orEmpty()
        val password = System.getenv("JOFI_CODEGEN_JDBC_PASSWORD").orEmpty()
        generate(CodegenDatabase(external, user, password), migrations, output)
    }
}

private const val EXPECTED_ARGUMENTS = 3

private fun generate(
    database: CodegenDatabase,
    migrations: String,
    output: String,
) {
    Flyway
        .configure()
        .dataSource(database.url, database.user, database.password)
        .locations("filesystem:$migrations")
        .load()
        .migrate()
    File(output).deleteRecursively()
    GenerationTool.generate(configuration(database, output))
}

private fun configuration(
    database: CodegenDatabase,
    output: String,
): Configuration =
    Configuration()
        .withLogging(Logging.WARN)
        .withJdbc(
            Jdbc()
                .withDriver("org.postgresql.Driver")
                .withUrl(database.url)
                .withUser(database.user)
                .withPassword(database.password),
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
