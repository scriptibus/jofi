// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.FinishReason
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.ToolCall
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.ai.chat.messages.ToolResponseMessage
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletionException

/** The pure mappings: stop reasons, provider errors, tool results and the capability table. */
class MappingTest {
    @ParameterizedTest
    @CsvSource(
        "STOP, STOP",
        "end_turn, STOP",
        "LENGTH, MAX_TOKENS",
        "max_tokens, MAX_TOKENS",
        "content_filter, CONTENT_FILTERED",
        "refusal, CONTENT_FILTERED",
        "something_new, STOP",
    )
    fun `stop reasons of every provider map to one vocabulary`(
        reported: String,
        expected: FinishReason,
    ) {
        ResponseAccumulator.finishReasonOf(reported, hasToolCalls = false) shouldBe expected
    }

    @Test
    fun `tool calls win over the reported stop reason`() {
        ResponseAccumulator.finishReasonOf("stop", hasToolCalls = true) shouldBe FinishReason.TOOL_CALLS
    }

    @Test
    fun `retry delays are read in seconds and ignored otherwise`() {
        ProviderFailures.fromStatus(ProviderError(429, " 12 ", ""), AiTask.CHAT) shouldBe
            AiResult.RateLimited(Duration.ofSeconds(12))
        ProviderFailures.fromStatus(ProviderError(429, "Wed, 30 Sep 2026 12:00:00 GMT", ""), AiTask.CHAT) shouldBe
            AiResult.RateLimited(null)
    }

    @Test
    fun `wrapped I-O failures are unavailable and unknown failures are rejected`() {
        ProviderFailures.map(CompletionException(IOException("reset")), AiTask.CHAT) shouldBe AiResult.Unavailable
        ProviderFailures.map(IllegalStateException("no choices"), AiTask.CHAT) shouldBe AiResult.Rejected(null)
    }

    @Test
    fun `provider errors print their status only`() {
        ProviderError(400, null, "your prompt was: secret").toString() shouldBe "ProviderError(status=400)"
    }

    @Test
    fun `consecutive tool results become one message named after their calls`() {
        val messages =
            PromptMapper.messages(
                listOf(
                    LlmMessage.User("Hi"),
                    LlmMessage.Assistant("", listOf(ToolCall("a", "first", "{}"), ToolCall("b", "second", "{}"))),
                    LlmMessage.ToolResult("a", "1"),
                    LlmMessage.ToolResult("b", "2"),
                    LlmMessage.User("Danke"),
                ),
            )

        messages shouldHaveSize 4
        val results = (messages[2] as ToolResponseMessage).responses
        results.map { it.name() } shouldBe listOf("first", "second")
    }

    @ParameterizedTest
    @CsvSource(
        "ANTHROPIC, claude-opus-4-1, true, 200000",
        "OPENAI, gpt-4.1-mini, true, 1047576",
        "OPENAI, o4-mini, true, 200000",
        "GEMINI, models/gemini-2.5-pro, true, 1048576",
        "MISTRAL, mistral-small-latest, true, 128000",
        "OPENAI, text-embedding-3-large, false, 8191",
    )
    fun `the capability table knows the model families`(
        kind: ProviderKind,
        model: String,
        toolUse: Boolean,
        context: Int,
    ) {
        val capabilities = CapabilityTable.of(kind, ModelName(model))

        capabilities.meets(Capability.ToolUse) shouldBe toolUse
        capabilities.contextSize shouldBe Capability.ContextSize(context)
    }

    @Test
    fun `speech models and unknown or local models`() {
        CapabilityTable.of(ProviderKind.OPENAI, ModelName("gpt-4o-mini-tts")).meets(Capability.TextToSpeech) shouldBe
            true
        CapabilityTable.of(ProviderKind.OPENAI, ModelName("gpt-4o-transcribe")).meets(Capability.SpeechToText) shouldBe
            true
        CapabilityTable.of(ProviderKind.MISTRAL, ModelName("mistral-embed")).meets(Capability.Embedding) shouldBe true
        CapabilityTable.of(ProviderKind.OPENAI, ModelName("claude-sonnet-4-5")) shouldBe ModelCapabilities.NONE
        CapabilityTable.of(ProviderKind.OPENAI_COMPATIBLE, ModelName("qwen3:8b")) shouldBe ModelCapabilities.NONE
    }
}
