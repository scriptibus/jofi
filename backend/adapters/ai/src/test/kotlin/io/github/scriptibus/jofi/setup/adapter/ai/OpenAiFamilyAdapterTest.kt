// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.FinishReason
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.ai.ToolCall
import io.github.scriptibus.jofi.shared.domain.ai.ToolDefinition
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * OpenAI and the providers reached through their OpenAI-compatible APIs (Gemini, Mistral, local
 * endpoints), against recorded fixtures: complete, tool calls, streaming, embeddings.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAiFamilyAdapterTest {
    private val stub = ProviderStub()
    private val adapter = stub.adapter()

    @BeforeEach
    fun reset() {
        stub.server.resetAll()
    }

    @AfterAll
    fun stop() {
        stub.close()
    }

    @ParameterizedTest
    @EnumSource(names = ["OPENAI", "GEMINI", "MISTRAL", "OPENAI_COMPATIBLE"])
    fun `completes a conversation with the resolved model and the stored key`(kind: ProviderKind) {
        val path = "${stub.pathOf(kind)}/chat/completions"
        stub.server.stubFor(post(path).willReturn(okJson(ProviderStub.fixture("openai/chat-completion.json"))))

        val result = adapter.complete(stub.target(kind, "gpt-4o-mini"), greeting())

        result shouldBe
            AiResult.Success(
                LlmResponse("Guten Tag! Wie kann ich helfen?", emptyList(), FinishReason.STOP, TokenUsage(21, 9)),
            )
        stub.server.verify(
            postRequestedFor(urlEqualTo(path))
                .withHeader("Authorization", equalTo("Bearer ${ProviderStub.KEY}"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o-mini")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", containing("Hallo"))),
        )
    }

    @ParameterizedTest
    @EnumSource(names = ["OPENAI", "GEMINI", "MISTRAL", "OPENAI_COMPATIBLE"])
    fun `streams text fragments and returns the full answer with its usage`(kind: ProviderKind) {
        val path = "${stub.pathOf(kind)}/chat/completions"
        stub.server.stubFor(post(path).willReturn(sse(ProviderStub.openAiStream("openai/chat-stream.json"))))
        val fragments = mutableListOf<String>()

        val result = adapter.stream(stub.target(kind), greeting(), { false }) { fragments += it }

        fragments shouldContainExactly listOf("Guten", " Tag", "!")
        result shouldBe AiResult.Success(LlmResponse("Guten Tag!", emptyList(), FinishReason.STOP, TokenUsage(21, 4)))
        stub.server.verify(
            postRequestedFor(urlEqualTo(path)).withRequestBody(matchingJsonPath("$.stream", equalTo("true"))),
        )
    }

    @ParameterizedTest
    @EnumSource(names = ["OPENAI", "GEMINI", "MISTRAL", "OPENAI_COMPATIBLE"])
    fun `embeds texts in request order`(kind: ProviderKind) {
        val path = "${stub.pathOf(kind)}/embeddings"
        stub.server.stubFor(post(path).willReturn(okJson(ProviderStub.fixture("openai/embeddings.json"))))

        val result =
            adapter.embed(
                stub.target(kind, "text-embedding-3-small"),
                EmbeddingRequest(listOf("Kotlin", "Spring")),
            )

        val response =
            result
                .shouldBeInstanceOf<AiResult.Success<*>>()
                .value
                .shouldBeInstanceOf<io.github.scriptibus.jofi.shared.domain.ai.EmbeddingResponse>()
        response.embeddings.map { it.vector } shouldContainExactly
            listOf(listOf(0.5f, 0.25f, -0.75f), listOf(0.25f, -0.5f, 0.125f))
        response.usage shouldBe TokenUsage(11, 0)
        stub.server.verify(
            postRequestedFor(urlEqualTo(path)).withRequestBody(matchingJsonPath("$.input[1]", equalTo("Spring"))),
        )
    }

    @ParameterizedTest
    @EnumSource(names = ["OPENAI", "OPENAI_COMPATIBLE"])
    fun `returns the tool calls the model asks for and sends the tools, never running them`(kind: ProviderKind) {
        val path = "${stub.pathOf(kind)}/chat/completions"
        stub.server.stubFor(post(path).willReturn(okJson(ProviderStub.fixture("openai/chat-tool-call.json"))))

        val result = adapter.complete(stub.target(kind), toolRequest())

        result shouldBe
            AiResult.Success(
                LlmResponse(
                    "",
                    listOf(ToolCall("call_Qx7", "find_company", """{"name":"ACME GmbH"}""")),
                    FinishReason.TOOL_CALLS,
                    TokenUsage(84, 17),
                ),
            )
        stub.server.verify(
            postRequestedFor(urlEqualTo(path))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("find_company")))
                .withRequestBody(matchingJsonPath("$.messages[2].tool_calls[0].id", equalTo("call_1")))
                .withRequestBody(matchingJsonPath("$.messages[3].tool_call_id", equalTo("call_1"))),
        )
    }

    private fun greeting() =
        LlmRequest(
            AiTask.CHAT,
            listOf(LlmMessage.System("Du bist Jofi."), LlmMessage.User("Hallo, ich bin Lucas.")),
        )

    private fun toolRequest() =
        LlmRequest(
            AiTask.CHAT,
            listOf(
                LlmMessage.System("Du bist Jofi."),
                LlmMessage.User("Was weißt du über ACME?"),
                LlmMessage.Assistant("", listOf(ToolCall("call_1", "list_notes", "{}"))),
                LlmMessage.ToolResult("call_1", "keine Notizen"),
            ),
            tools =
                listOf(
                    ToolDefinition(
                        "find_company",
                        "Finds a company by name",
                        """{"type":"object","properties":{"name":{"type":"string"}}}""",
                    ),
                    ToolDefinition("list_notes", "Lists notes", """{"type":"object","properties":{}}"""),
                ),
        )

    companion object {
        fun okJson(body: String): ResponseDefinitionBuilder =
            aResponse().withHeader("Content-Type", "application/json").withBody(body)

        fun sse(body: String): ResponseDefinitionBuilder =
            aResponse().withHeader("Content-Type", "text/event-stream").withBody(body)
    }
}
