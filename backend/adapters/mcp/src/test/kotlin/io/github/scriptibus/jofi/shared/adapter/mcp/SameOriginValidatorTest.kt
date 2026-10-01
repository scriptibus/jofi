// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SameOriginValidatorTest {
    private val validator = SameOriginValidator()

    @ParameterizedTest
    @CsvSource(
        "http://localhost:8080, localhost:8080",
        "http://127.0.0.1:8080, 127.0.0.1:8080",
        "https://jofi.example, jofi.example",
        "https://jofi.example, jofi.example:443",
        "https://JOFI.example:443, jofi.example",
        "http://[::1]:8080, [::1]:8080",
    )
    fun `a request from Jofi's own origin passes`(
        origin: String,
        host: String,
    ) {
        shouldNotThrowAny { validator.validateHeaders(mapOf("origin" to listOf(origin), "host" to listOf(host))) }
    }

    @ParameterizedTest
    @CsvSource(
        "http://evil.example, localhost:8080",
        "http://localhost:9999, localhost:8080",
        "http://jofi.example, jofi.example:443",
        "null, localhost:8080",
        "file://, localhost:8080",
        "http://localhost:8080/path, localhost:8080",
    )
    fun `a request from any other origin is refused with 403`(
        origin: String,
        host: String,
    ) {
        val refused =
            shouldThrow<ServerTransportSecurityException> {
                validator.validateHeaders(mapOf("origin" to listOf(origin), "host" to listOf(host)))
            }

        refused.statusCode shouldBe 403
    }

    @ParameterizedTest
    @CsvSource("Origin, Host", "origin, host")
    fun `header names are matched in any case, and an Origin without a Host is refused`(
        originHeader: String,
        hostHeader: String,
    ) {
        shouldNotThrowAny {
            validator.validateHeaders(
                mapOf(originHeader to listOf("http://localhost:8080"), hostHeader to listOf("localhost:8080")),
            )
        }
        shouldThrow<ServerTransportSecurityException> {
            validator.validateHeaders(mapOf(originHeader to listOf("http://localhost:8080")))
        }
    }

    @Test
    fun `a request without an Origin is not a browser's and passes`() {
        shouldNotThrowAny { validator.validateHeaders(mapOf("host" to listOf("localhost:8080"))) }
    }
}
