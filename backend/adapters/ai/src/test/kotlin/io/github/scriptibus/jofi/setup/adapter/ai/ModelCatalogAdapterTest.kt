// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.okJson
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Model listing per provider, combined with the capability table into detected profiles. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelCatalogAdapterTest {
    private val stub = ProviderStub()
    private val catalog = stub.catalog()

    @BeforeEach
    fun reset() {
        stub.server.resetAll()
    }

    @AfterAll
    fun stop() {
        stub.close()
    }

    @Test
    fun `lists OpenAI models with their known capabilities`() {
        stub.server.stubFor(get("/openai/v1/models").willReturn(okJson(ProviderStub.fixture("openai/models.json"))))
        val provider = stub.provider(ProviderKind.OPENAI)

        val profiles = catalog.detect(provider).profiles()

        profiles.map { it.model.value } shouldBe
            listOf("gpt-4o-mini", "text-embedding-3-small", "whisper-1", "dall-e-3")
        profiles[0] shouldBe
            ModelCapabilityProfile(
                provider.id,
                ModelName("gpt-4o-mini"),
                ModelCapabilities(setOf(Capability.ToolUse, Capability.Streaming, Capability.ContextSize(128_000))),
                CapabilitySource.DETECTED,
                ProviderStub.CLOCK.instant(),
            )
        profiles[1].capabilities.meets(Capability.Embedding) shouldBe true
        profiles[2].capabilities.meets(Capability.SpeechToText) shouldBe true
        profiles[3].capabilities shouldBe ModelCapabilities.NONE
        stub.server.verify(
            getRequestedFor(
                urlPathEqualTo("/openai/v1/models"),
            ).withHeader("Authorization", equalTo("Bearer ${ProviderStub.KEY}")),
        )
    }

    @Test
    fun `strips the models prefix Gemini lists`() {
        stub.server.stubFor(
            get("/gemini/v1beta/openai/models")
                .willReturn(
                    okJson("""{"object":"list","data":[{"id":"models/gemini-2.5-flash","object":"model"}]}"""),
                ),
        )

        val profiles = catalog.detect(stub.provider(ProviderKind.GEMINI)).profiles()

        profiles.single().model shouldBe ModelName("gemini-2.5-flash")
        profiles.single().capabilities.meets(Capability.ContextSize(1_000_000)) shouldBe true
    }

    @Test
    fun `takes Anthropic's reported input limit over the table`() {
        stub.server.stubFor(
            get(
                urlPathEqualTo("/anthropic/v1/models"),
            ).willReturn(okJson(ProviderStub.fixture("anthropic/models.json"))),
        )

        val profiles = catalog.detect(stub.provider(ProviderKind.ANTHROPIC)).profiles()

        profiles[0].capabilities.contextSize shouldBe Capability.ContextSize(1_000_000)
        profiles[1].capabilities.contextSize shouldBe Capability.ContextSize(200_000)
        profiles.all { it.capabilities.meets(Capability.ToolUse) } shouldBe true
    }

    @Test
    fun `a failing listing is a sealed result`() {
        stub.server.stubFor(
            get("/mistral/v1/models").willReturn(okJson("""{"message":"Unauthorized"}""").withStatus(401)),
        )

        catalog.detect(stub.provider(ProviderKind.MISTRAL)) shouldBe AiResult.AuthenticationFailed
    }

    @Test
    fun `knows models without calling the provider`() {
        catalog
            .knownCapabilities(
                ProviderKind.MISTRAL,
                ModelName("mistral-large-latest"),
            ).meets(Capability.ToolUse) shouldBe
            true
        catalog.knownCapabilities(ProviderKind.OPENAI_COMPATIBLE, ModelName("llama3.1:8b")) shouldBe
            ModelCapabilities.NONE
    }

    private fun AiResult<List<ModelCapabilityProfile>>.profiles(): List<ModelCapabilityProfile> =
        shouldBeInstanceOf<AiResult.Success<List<ModelCapabilityProfile>>>().value
}
