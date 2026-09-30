// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * The production datasource path without a database: startup must fail when `JOFI_DB_PASSWORD` is
 * unset or blank, and must still work with a placeholder password and Flyway off, as in the Docker
 * image's AOT training run (no database exists at build time; the pool connects lazily).
 */
class DatabasePasswordTest {
    @Test
    fun `startup fails when JOFI_DB_PASSWORD is not set`() {
        assumeTrue(System.getenv("JOFI_DB_PASSWORD") == null, "JOFI_DB_PASSWORD is set in this environment")

        startupFailure(OFFLINE) shouldContain "JOFI_DB_PASSWORD"
    }

    @Test
    fun `startup fails when JOFI_DB_PASSWORD is blank`() {
        startupFailure(OFFLINE + "--JOFI_DB_PASSWORD= ") shouldContain "JOFI_DB_PASSWORD"
    }

    @Test
    fun `the AOT training run starts with a placeholder password and Flyway off`() {
        start(OFFLINE + "--JOFI_DB_PASSWORD=aot-training-placeholder").use { context ->
            context.isActive shouldBe true
        }
    }

    private fun start(args: List<String>) =
        SpringApplicationBuilder(JofiApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(*args.toTypedArray())

    private fun startupFailure(args: List<String>): String {
        val failure = shouldThrowAny { start(args).close() }
        return generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    private companion object {
        val OFFLINE =
            listOf(
                "--JOFI_DB_URL=jdbc:postgresql://localhost:5432/aot-training",
                "--spring.flyway.enabled=false",
            )
    }
}
