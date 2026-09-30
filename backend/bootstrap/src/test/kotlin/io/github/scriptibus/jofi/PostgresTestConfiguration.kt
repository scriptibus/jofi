// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** A throwaway PostgreSQL (pinned pgvector image) that replaces the configured datasource. */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = newPostgresContainer()
}

/** Distinctive, so tests can assert it never shows up in the logs. */
const val TEST_DB_PASSWORD = "log-canary-log-canary"

/** The pinned image from `gradle.properties`, passed in by the Gradle test task. */
fun newPostgresContainer(): PostgreSQLContainer {
    val image = requireNotNull(System.getProperty("jofi.postgresImage")) { "jofi.postgresImage is not set" }
    return PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
        .withPassword(TEST_DB_PASSWORD)
}
