// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The production path: the datasource comes from the `JOFI_DB_*` environment variables read by
 * `application.yaml` (no service connection). Datasource, Flyway and jOOQ log verbosely here, and
 * the whole startup output must not contain the password (AGENTS.md §6, privacy).
 */
@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(
    properties = [
        "logging.level.com.zaxxer.hikari=DEBUG",
        "logging.level.org.flywaydb=DEBUG",
        "logging.level.org.jooq=DEBUG",
    ],
)
class DatabaseConfigurationTest(
    @param:Autowired private val dsl: DSLContext,
) {
    @Test
    fun `connects with the credentials from the environment`() {
        dsl.fetchValue("select current_user") shouldBe postgres.username
    }

    @Test
    fun `startup logs never contain the database password`(output: CapturedOutput) {
        // Proves the verbose datasource configuration was actually captured.
        output.all shouldContain "jdbcUrl"
        output.all shouldNotContain TEST_DB_PASSWORD
    }

    companion object {
        private val postgres = newPostgresContainer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun environment(registry: DynamicPropertyRegistry) {
            registry.add("JOFI_DB_URL", postgres::getJdbcUrl)
            registry.add("JOFI_DB_USERNAME", postgres::getUsername)
            registry.add("JOFI_DB_PASSWORD", postgres::getPassword)
        }
    }
}
