// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.config.McpConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

class McpConfigurationTest {
    private val lifetime = Duration.ofMinutes(5)

    @Test
    fun `a timeout that is positive and below the token lifetime is accepted`() {
        McpConfiguration.validConfirmationTimeout(Duration.ofSeconds(270), lifetime) shouldBe Duration.ofSeconds(270)
        McpConfiguration.validConfirmationTimeout(Duration.ofMillis(1), lifetime) shouldBe Duration.ofMillis(1)
    }

    @Test
    fun `zero, negative, equal and longer timeouts fail the start with a clear message`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1), lifetime, Duration.ofMinutes(6)).forEach { timeout ->
            shouldThrow<IllegalArgumentException> { McpConfiguration.validConfirmationTimeout(timeout, lifetime) }
                .message shouldContain "jofi.mcp.confirmation-timeout must be positive and shorter than"
        }
    }
}
