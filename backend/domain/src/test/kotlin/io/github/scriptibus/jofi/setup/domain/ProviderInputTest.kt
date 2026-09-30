// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.UUID

class ProviderInputTest {
    private fun valid(result: SetupValidation<ProviderSettings>): ProviderSettings =
        result.shouldBeInstanceOf<SetupValidation.Valid<ProviderSettings>>().value

    private fun violations(result: SetupValidation<*>): List<SetupViolation> =
        result.shouldBeInstanceOf<SetupValidation.Invalid>().violations

    @Test
    fun `the name is normalized to NFC and trimmed, blank optional fields are absent`() {
        val decomposed = "Métis"

        val settings = valid(ProviderInput("  $decomposed ", " ", " ").validate(ProviderKind.OPENAI, keyStored = true))

        settings.displayName shouldBe "Métis"
        settings.baseUri shouldBe null
        settings.apiKey shouldBe null
    }

    @ParameterizedTest
    @EnumSource(ProviderKind::class, names = ["ANTHROPIC", "OPENAI", "GEMINI", "MISTRAL"])
    fun `a cloud provider needs a key unless one is stored, and takes no base URL`(kind: ProviderKind) {
        violations(ProviderInput("Cloud", "https://example.com", null).validate(kind, keyStored = false)) shouldBe
            listOf(
                SetupViolation(SetupField.BASE_URL, SetupViolationKind.NOT_ALLOWED),
                SetupViolation(SetupField.API_KEY, SetupViolationKind.REQUIRED),
            )
        valid(ProviderInput("Cloud", null, " sk-1 ").validate(kind, keyStored = false)).apiKey shouldBe
            SecretValue("sk-1")
        valid(ProviderInput("Cloud", null, null).validate(kind, keyStored = true)).apiKey shouldBe null
    }

    @Test
    fun `an OpenAI-compatible endpoint needs a base URL and may go without a key`() {
        val kind = ProviderKind.OPENAI_COMPATIBLE

        violations(ProviderInput("Ollama", null, null).validate(kind, keyStored = false)) shouldBe
            listOf(SetupViolation(SetupField.BASE_URL, SetupViolationKind.REQUIRED))
        valid(
            ProviderInput("Ollama", " http://[::1]:11434/v1 ", null).validate(kind, keyStored = false),
        ).baseUri shouldBe
            URI("http://[::1]:11434/v1")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "localhost:11434", "ftp://example.com", "http://user:pw@example.com/v1", "https://example.com/v1?key=1",
            "https://example.com/#x", "http://exa mple.com", "http:///v1", "file:///etc/passwd",
        ],
    )
    fun `a base URL must be an absolute http(s) URL without credentials`(url: String) {
        violations(
            ProviderInput("Local", url, null).validate(ProviderKind.OPENAI_COMPATIBLE, keyStored = false),
        ) shouldBe
            listOf(SetupViolation(SetupField.BASE_URL, SetupViolationKind.INVALID_URL))
    }

    @Test
    fun `texts at their limit pass, one more character does not`() {
        val kind = ProviderKind.OPENAI_COMPATIBLE
        val url = "https://example.com/" + "a".repeat(ProviderInput.MAX_BASE_URL - "https://example.com/".length)
        val name = "n".repeat(ProviderInput.MAX_DISPLAY_NAME)
        val key = "k".repeat(ProviderInput.MAX_KEY)

        valid(ProviderInput(name, url, key).validate(kind, keyStored = false)).displayName shouldBe name
        violations(ProviderInput(name + "n", url + "a", key + "k").validate(kind, keyStored = false)) shouldBe
            listOf(
                SetupViolation(SetupField.DISPLAY_NAME, SetupViolationKind.TOO_LONG),
                SetupViolation(SetupField.BASE_URL, SetupViolationKind.TOO_LONG),
                SetupViolation(SetupField.API_KEY, SetupViolationKind.TOO_LONG),
            )
    }

    @Test
    fun `the input never prints its key`() {
        ProviderInput("OpenAI", null, "sk-secret").toString() shouldNotContain "sk-secret"
    }

    @Test
    fun `capability input names a model and a positive context window`() {
        val validated = CapabilityInput(" qwen3 ", setOf(CapabilityName.TOOL_USE), 4_096).validate()

        validated shouldBe
            SetupValidation.Valid(
                ModelName("qwen3") to ModelCapabilities(setOf(Capability.ToolUse, Capability.ContextSize(4_096))),
            )
        violations(CapabilityInput("m".repeat(CapabilityInput.MAX_MODEL_NAME + 1), emptySet(), -1).validate()) shouldBe
            listOf(
                SetupViolation(SetupField.MODEL, SetupViolationKind.TOO_LONG),
                SetupViolation(SetupField.CONTEXT_WINDOW, SetupViolationKind.OUT_OF_RANGE),
            )
        CapabilityInput.modelName(" ") shouldBe
            SetupValidation.Invalid(listOf(SetupViolation(SetupField.MODEL, SetupViolationKind.REQUIRED)))
    }

    @Test
    fun `providers and assignments name their changelog entities`() {
        val id = ProviderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

        id.toEntityRef().type shouldBe "ai_provider"
        id.toEntityRef().id shouldBe "00000000-0000-0000-0000-000000000001"
        ModelAssignment.entityRef(AiTask.CHAT).id shouldBe "CHAT"
    }
}
