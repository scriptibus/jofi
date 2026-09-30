// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.post
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.okJson
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.sse
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibility
import io.github.scriptibus.jofi.shared.domain.ai.ContentPart
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.ContentSourceType
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.ai.ToolCall
import io.github.scriptibus.jofi.shared.domain.ai.ToolDefinition
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The gateway over the real Spring AI adapter and the guarded transport, with WireMock as the
 * provider: what the provider receives on the wire never contains flagged content, and every call
 * is metered with the real price table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiGatewayWireTest {
    private val stub = ProviderStub()
    private val setup = InMemorySetup()
    private val visibility = FakeVisibility(RULES)
    private val gateway =
        AiGatewayAdapter(
            stub.adapter(),
            AiRouter(setup.assignmentPort, setup.providerPort, setup.capabilityPort, stub.catalog()),
            NeverSendGuard(visibility),
            AiMeter(setup.costPort, setup.budgetPort, PriceTableFile.load(), ProviderStub.CLOCK),
        )

    @BeforeEach
    fun reset() {
        stub.server.resetAll()
        setup.costs.clear()
    }

    @AfterAll
    fun stop() {
        stub.close()
    }

    private fun route(
        task: AiTask,
        kind: ProviderKind,
        model: String,
    ) {
        val target = stub.target(kind, model)
        setup.providers[target.provider.id] = target.provider
        setup.assignments[task] = ModelAssignment(task, target.provider.id, target.model)
        setup.profiles[target.provider.id to target.model] =
            ModelCapabilityProfile(
                target.provider.id,
                target.model,
                ModelCapabilities(setOf(Capability.ToolUse, Capability.Streaming, Capability.Embedding)),
                CapabilitySource.USER,
                ProviderStub.CLOCK.instant(),
            )
    }

    @ParameterizedTest
    @EnumSource(names = ["OPENAI", "ANTHROPIC"])
    fun `flagged content never reaches the provider, and the call is metered`(kind: ProviderKind) {
        val path = if (kind == ProviderKind.ANTHROPIC) "/anthropic/v1/messages" else "/openai/v1/chat/completions"
        val answer = if (kind == ProviderKind.ANTHROPIC) "anthropic/message.json" else "openai/chat-completion.json"
        stub.server.stubFor(post(path).willReturn(okJson(ProviderStub.fixture(answer))))
        route(AiTask.CHAT, kind, if (kind == ProviderKind.ANTHROPIC) "claude-haiku-4-5" else "gpt-4o-mini")

        gateway.complete(flaggedConversation()).shouldBeInstanceOf<AiResult.Success<*>>()

        assertProviderSawNothingFlagged(expectedRequests = 1)
        val entry = setup.costs.single()
        entry.task shouldBe AiTask.CHAT
        entry.providerKind shouldBe kind
        entry.estimatedCost shouldNotBe null
    }

    @Test
    fun `a stream is filtered and metered on completion`() {
        stub.server.stubFor(
            post("/openai/v1/chat/completions").willReturn(sse(ProviderStub.openAiStream("openai/chat-stream.json"))),
        )
        route(AiTask.CHAT, ProviderKind.OPENAI, "gpt-4o-mini")

        gateway.stream(flaggedConversation()) {}.shouldBeInstanceOf<AiResult.Success<*>>()

        assertProviderSawNothingFlagged(expectedRequests = 1)
        // 21 * $0.15 + 4 * $0.60 per million tokens = 5.55 micro dollars (price table, 2026-09-30)
        setup.costs.single().usage shouldBe TokenUsage(21, 4)
        setup.costs.single().estimatedCost shouldBe Money.usd(6)
    }

    @Test
    fun `an embedding of a flagged item never leaves, other inputs arrive redacted`() {
        stub.server.stubFor(
            post("/openai/v1/embeddings").willReturn(okJson(ProviderStub.fixture("openai/embeddings.json"))),
        )
        route(AiTask.EMBEDDING, ProviderKind.OPENAI, "text-embedding-3-small")

        gateway.embed(EmbeddingRequest(listOf(ContentPart.Sourced(ADDRESS_TEXT, ADDRESS)))) shouldBe
            AiResult.Withheld(AiTask.EMBEDDING)
        stub.server.findAll(anyRequestedFor(anyUrl())) shouldHaveSize 0

        gateway
            .embed(
                EmbeddingRequest.ofTexts(listOf("Kotlin", "Tel. $PHONE")),
            ).shouldBeInstanceOf<AiResult.Success<*>>()
        assertProviderSawNothingFlagged(expectedRequests = 1)
    }

    @Test
    fun `an item the filter does not know is never sent`() {
        route(AiTask.CHAT, ProviderKind.OPENAI, "gpt-4o-mini")
        val unknown = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "new-entry")

        gateway.complete(
            LlmRequest(AiTask.CHAT, listOf(LlmMessage.User(listOf(ContentPart.Sourced("x", unknown))))),
        ) shouldBe
            AiResult.PrivacyFilterFailed(AiTask.CHAT)
        stub.server.findAll(anyRequestedFor(anyUrl())) shouldHaveSize 0
    }

    private fun flaggedConversation(): LlmRequest {
        val flagged = ContentPart.Sourced(ADDRESS_TEXT, ADDRESS)
        return LlmRequest(
            AiTask.CHAT,
            listOf(
                LlmMessage.System(listOf(ContentPart.Plain("Du bist Jofi. Profil: "), flagged)),
                LlmMessage.User("Meine Nummer ist $PHONE, schreib sie in die Notiz."),
                LlmMessage.Assistant("", listOf(ToolCall("call_1", "save_note", """{"text":"$PHONE"}"""))),
                LlmMessage.ToolResult("call_1", listOf(ContentPart.Plain("Gespeichert: "), flagged)),
            ),
            tools = listOf(ToolDefinition("save_note", "Saves a note", """{"type":"object"}""")),
        )
    }

    private fun assertProviderSawNothingFlagged(expectedRequests: Int) {
        val received = stub.server.findAll(anyRequestedFor(anyUrl()))
        received shouldHaveSize expectedRequests
        received.forEach { request ->
            val body = request.bodyAsString
            body shouldNotContain "Musterstra"
            body shouldNotContain "1234567"
            body shouldContain NeverSendFilter.REDACTION
        }
    }

    private companion object {
        const val ADDRESS_TEXT = "Musterstraße 5, 12345 Berlin"
        const val PHONE = "0170 1234567"
        val ADDRESS = ContentSource(ContentSourceType.KNOWLEDGE_ENTRY, "profile/address")
        val RULES =
            NeverSendRules(
                mapOf(ADDRESS to AiVisibility.NEVER_SEND),
                setOf(FlaggedValue(ADDRESS_TEXT), FlaggedValue(PHONE)),
            )
    }
}
