// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.UUID

class ProviderConfigTest {
    private val id = ProviderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val key = SecretId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val ollama = URI("http://localhost:11434/v1")

    @ParameterizedTest
    @EnumSource(ProviderKind::class, names = ["ANTHROPIC", "OPENAI", "GEMINI", "MISTRAL"])
    fun `cloud providers need a key and take no base URL`(kind: ProviderKind) {
        ProviderConfig(id, "Cloud", kind, apiKey = key).apiKey shouldBe key
        shouldThrow<IllegalArgumentException> { ProviderConfig(id, "Cloud", kind, apiKey = null) }
        shouldThrow<IllegalArgumentException> { ProviderConfig(id, "Cloud", kind, apiKey = key, baseUri = ollama) }
    }

    @Test
    fun `an OpenAI-compatible endpoint needs a base URL and may run without a key`() {
        ProviderConfig(id, "Ollama", ProviderKind.OPENAI_COMPATIBLE, apiKey = null, baseUri = ollama).baseUri shouldBe
            ollama
        ProviderConfig(id, "OpenRouter", ProviderKind.OPENAI_COMPATIBLE, key, URI("https://openrouter.ai/api/v1"))
            .apiKey shouldBe key
        shouldThrow<IllegalArgumentException> {
            ProviderConfig(id, "Ollama", ProviderKind.OPENAI_COMPATIBLE, apiKey = null)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["file:///etc/passwd", "ftp://example.org", "/v1", "http:///v1"])
    fun `a base URL is an absolute http(s) URL with a host`(invalid: String) {
        shouldThrow<IllegalArgumentException> {
            ProviderConfig(id, "Local", ProviderKind.OPENAI_COMPATIBLE, apiKey = null, baseUri = URI(invalid))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://user:xxxx@proxy.example.org/v1",
            "https://token@proxy.example.org/v1",
            "https://proxy.example.org/v1?key=xxxx",
            "https://proxy.example.org/v1?",
            "https://proxy.example.org/v1#key=xxxx",
        ],
    )
    fun `a base URL carries no credentials, query or fragment`(invalid: String) {
        shouldThrow<IllegalArgumentException> {
            ProviderConfig(id, "Proxy", ProviderKind.OPENAI_COMPATIBLE, apiKey = key, baseUri = URI(invalid))
        }
    }

    @Test
    fun `a provider needs a display name`() {
        shouldThrow<IllegalArgumentException> { ProviderConfig(id, " ", ProviderKind.ANTHROPIC, key) }
    }

    @Test
    fun `a provider config never holds the key itself`() {
        val config = ProviderConfig(id, "Claude", ProviderKind.ANTHROPIC, key)

        config.toString() shouldBe
            "ProviderConfig(id=ProviderId(value=$ID), displayName=Claude, kind=ANTHROPIC, " +
            "apiKey=SecretId(value=$KEY), baseUri=null)"
    }

    private companion object {
        const val ID = "00000000-0000-0000-0000-000000000001"
        const val KEY = "00000000-0000-0000-0000-000000000002"
    }
}
