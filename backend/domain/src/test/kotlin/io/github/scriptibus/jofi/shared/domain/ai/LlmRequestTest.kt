// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class LlmRequestTest {
    private val question = listOf(LlmMessage.User("Summarise this posting"))
    private val searchTool = ToolDefinition("search_applications", "Finds applications", """{"type":"object"}""")

    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `carries its task, and only text-generation tasks are accepted`(task: AiTask) {
        if (task.kind == AiTaskKind.TEXT_GENERATION) {
            LlmRequest(task, question).task shouldBe task
        } else {
            shouldThrow<IllegalArgumentException> { LlmRequest(task, question) }
        }
    }

    @ParameterizedTest
    @EnumSource(AiTask::class)
    fun `only tasks that may use tools get tools`(task: AiTask) {
        if (task.kind != AiTaskKind.TEXT_GENERATION) return

        if (task.toolsAllowed) {
            LlmRequest(task, question, tools = listOf(searchTool)).tools shouldBe listOf(searchTool)
        } else {
            shouldThrow<IllegalArgumentException> { LlmRequest(task, question, tools = listOf(searchTool)) }
                .message shouldContain "T2"
        }
    }

    @Test
    fun `tasks that read untrusted input never allow tools`() {
        listOf(
            AiTask.SCANNER_PRE_SCORING,
            AiTask.CLASSIFICATION,
            AiTask.LANGUAGE_TONE_DETECTION,
            AiTask.EXTRACTION,
            AiTask.DOCUMENT_GENERATION,
        ).forEach { it.toolsAllowed shouldBe false }
    }

    @Test
    fun `needs messages, unique tool names and a positive output limit`() {
        shouldThrow<IllegalArgumentException> { LlmRequest(AiTask.CHAT, emptyList()) }
        shouldThrow<IllegalArgumentException> {
            LlmRequest(
                AiTask.CHAT,
                question,
                tools = listOf(searchTool, searchTool),
            )
        }
        shouldThrow<IllegalArgumentException> { LlmRequest(AiTask.CHAT, question, maxOutputTokens = 0) }
        LlmRequest(AiTask.CHAT, question, maxOutputTokens = 1).maxOutputTokens shouldBe 1
    }

    @Test
    fun `an output schema is optional, never blank, and printed only as present`() {
        shouldThrow<IllegalArgumentException> { LlmRequest(AiTask.EXTRACTION, question, outputSchema = " ") }
        val structured = LlmRequest(AiTask.EXTRACTION, question, outputSchema = """{"type":"object"}""")

        structured.toString() shouldContain "structured=true"
        structured.toString() shouldNotContain "object"
        LlmRequest(AiTask.EXTRACTION, question).toString() shouldContain "structured=false"
    }

    @Test
    fun `messages reject blank content`() {
        shouldThrow<IllegalArgumentException> { LlmMessage.System(" ") }
        shouldThrow<IllegalArgumentException> { LlmMessage.User("") }
        shouldThrow<IllegalArgumentException> { LlmMessage.Assistant(" ") }
        shouldThrow<IllegalArgumentException> { LlmMessage.ToolResult(" ", "{}") }
        LlmMessage.Assistant("", listOf(ToolCall("call-1", "search_applications", "{}"))).text shouldBe ""
        LlmMessage.ToolResult("call-1", "").content shouldBe ""
    }

    @Test
    fun `tool definitions and calls validate names`() {
        shouldThrow<IllegalArgumentException> { ToolDefinition("has space", "d", "{}") }
        shouldThrow<IllegalArgumentException> { ToolDefinition("x".repeat(65), "d", "{}") }
        shouldThrow<IllegalArgumentException> { ToolDefinition("ok", " ", "{}") }
        shouldThrow<IllegalArgumentException> { ToolDefinition("ok", "d", " ") }
        shouldThrow<IllegalArgumentException> { ToolCall(" ", "search", "{}") }
        shouldThrow<IllegalArgumentException> { ToolCall("call-1", " ", "{}") }
    }

    @Test
    fun `a response that stopped for tools contains tool calls`() {
        val call = ToolCall("call-1", "search_applications", """{"q":"Kotlin"}""")

        LlmResponse("", listOf(call), FinishReason.TOOL_CALLS, TokenUsage.NONE).toolCalls shouldBe listOf(call)
        shouldThrow<IllegalArgumentException> { LlmResponse("", emptyList(), FinishReason.TOOL_CALLS, TokenUsage.NONE) }
        LlmResponse("Done", emptyList(), FinishReason.STOP, TokenUsage(10, 2)).usage.totalTokens shouldBe 12
    }

    @Test
    fun `requests, messages, tool calls and responses print roles and sizes, never content`() {
        val secret = "Max Mustermann, born 1990"
        val call = ToolCall("call-1", "search_applications", """{"q":"$secret"}""")
        val request =
            LlmRequest(
                AiTask.CHAT,
                listOf(
                    LlmMessage.System("You help $secret"),
                    LlmMessage.User(secret),
                    LlmMessage.Assistant(secret, listOf(call)),
                    LlmMessage.ToolResult("call-1", secret),
                ),
                tools = listOf(searchTool),
            )
        val response = LlmResponse(secret, listOf(call), FinishReason.TOOL_CALLS, TokenUsage(3, 4))

        listOf(request.toString(), response.toString(), call.toString()).forEach { it shouldNotContain "Mustermann" }
        request.toString() shouldContain "User(chars=${secret.length}, parts=1)"
        request.toString() shouldContain "search_applications"
        response.toString() shouldContain "TOOL_CALLS"
        EmbeddingRequest.ofTexts(listOf(secret)).toString() shouldNotContain "Mustermann"
        EmbeddingRequest.ofTexts(listOf(secret)).toString() shouldContain "texts=1"
    }

    @Test
    fun `token usage is non-negative and adds up`() {
        TokenUsage(10, 5) + TokenUsage(1, 2) shouldBe TokenUsage(11, 7)
        shouldThrow<IllegalArgumentException> { TokenUsage(-1, 0) }
        shouldThrow<IllegalArgumentException> { TokenUsage(0, -1) }
    }
}
