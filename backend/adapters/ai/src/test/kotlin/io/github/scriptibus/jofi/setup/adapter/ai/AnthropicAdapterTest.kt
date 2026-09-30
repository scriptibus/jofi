// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.okJson
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.sse
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.FinishReason
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.ai.ToolCall
import io.github.scriptibus.jofi.shared.domain.ai.ToolDefinition
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Anthropic through Spring AI and the Anthropic SDK over the guarded transport, against recorded fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicAdapterTest {
    private val stub = ProviderStub()
    private val adapter = stub.adapter()
    private val messagesPath = "/anthropic/v1/messages"

    @BeforeEach
    fun reset() {
        stub.server.resetAll()
    }

    @AfterAll
    fun stop() {
        stub.close()
    }

    @Test
    fun `completes a conversation with the key and API version headers`() {
        stub.server.stubFor(post(messagesPath).willReturn(okJson(ProviderStub.fixture("anthropic/message.json"))))
        val request =
            LlmRequest(
                AiTask.CHAT,
                listOf(LlmMessage.System("Du bist Jofi."), LlmMessage.User("Hallo")),
                maxOutputTokens = 512,
            )

        val result = adapter.complete(stub.target(ProviderKind.ANTHROPIC, "claude-sonnet-4-5"), request)

        result shouldBe
            AiResult.Success(
                LlmResponse("Guten Tag! Wie kann ich helfen?", emptyList(), FinishReason.STOP, TokenUsage(24, 11)),
            )
        stub.server.verify(
            postRequestedFor(urlEqualTo(messagesPath))
                .withHeader("x-api-key", equalTo(ProviderStub.KEY))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-sonnet-4-5")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("512")))
                .withRequestBody(matchingJsonPath("$.system", equalTo("Du bist Jofi."))),
        )
    }

    @Test
    fun `returns tool use and sends tool results back in one turn`() {
        stub.server.stubFor(
            post(messagesPath).willReturn(okJson(ProviderStub.fixture("anthropic/message-tool-use.json"))),
        )
        val request = toolRoundTrip()

        val result = adapter.complete(stub.target(ProviderKind.ANTHROPIC), request)

        result shouldBe
            AiResult.Success(
                LlmResponse(
                    "Ich suche die Firma.",
                    listOf(ToolCall("toolu_01A09q90qw90lq917835lq9", "find_company", """{"name":"ACME GmbH"}""")),
                    FinishReason.TOOL_CALLS,
                    TokenUsage(92, 41),
                ),
            )
        stub.server.verify(
            postRequestedFor(urlEqualTo(messagesPath))
                .withRequestBody(matchingJsonPath("$.tools[0].name", equalTo("find_company")))
                .withRequestBody(matchingJsonPath("$.messages[2].content[1].tool_use_id", equalTo("toolu_b"))),
        )
    }

    @Test
    fun `streams text fragments and returns usage from the final event`() {
        stub.server.stubFor(
            post(messagesPath).willReturn(sse(ProviderStub.anthropicStream("anthropic/message-stream.json"))),
        )
        val fragments = mutableListOf<String>()

        val result =
            adapter.stream(
                stub.target(
                    ProviderKind.ANTHROPIC,
                ),
                LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("Hallo"))),
                {
                    false
                },
            ) {
                fragments += it
            }

        fragments shouldContainExactly listOf("Guten", " Tag", "!")
        result shouldBe AiResult.Success(LlmResponse("Guten Tag!", emptyList(), FinishReason.STOP, TokenUsage(24, 4)))
    }

    @Test
    fun `maps Anthropic errors to sealed results`() {
        stub.server.stubFor(
            post(
                messagesPath,
            ).willReturn(okJson(ProviderStub.fixture("anthropic/error-prompt-too-long.json")).withStatus(400)),
        )
        complete() shouldBe AiResult.ContextTooLong

        stub.server.stubFor(
            post(
                messagesPath,
            ).willReturn(okJson(ProviderStub.fixture("anthropic/error-overloaded.json")).withStatus(529)),
        )
        complete() shouldBe AiResult.Unavailable

        stub.server.stubFor(
            post(
                messagesPath,
            ).willReturn(
                okJson(
                    """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
                ).withStatus(401),
            ),
        )
        complete() shouldBe AiResult.AuthenticationFailed
    }

    private fun toolRoundTrip() =
        LlmRequest(
            AiTask.KNOWLEDGE_INTERVIEW,
            listOf(
                LlmMessage.User("Was weißt du über ACME?"),
                LlmMessage.Assistant(
                    "",
                    listOf(ToolCall("toolu_a", "list_notes", "{}"), ToolCall("toolu_b", "list_notes", "{}")),
                ),
                LlmMessage.ToolResult("toolu_a", "keine"),
                LlmMessage.ToolResult("toolu_b", "auch keine"),
            ),
            tools = listOf(ToolDefinition("find_company", "Finds a company", """{"type":"object"}""")),
        )

    private fun complete() =
        adapter.complete(stub.target(ProviderKind.ANTHROPIC), LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("Hallo"))))
}
