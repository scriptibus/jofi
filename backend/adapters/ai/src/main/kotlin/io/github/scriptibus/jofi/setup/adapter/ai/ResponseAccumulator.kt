// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.shared.domain.ai.FinishReason
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.shared.domain.ai.ToolCall
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import java.util.Locale

/**
 * Builds an [LlmResponse] from Spring AI responses: one for a complete call, or the fragments of a
 * stream in order. Text is concatenated; tool calls, finish reason and usage take the latest value
 * a fragment reports (OpenAI sends usage in its last chunk, Anthropic in `message_delta`).
 */
internal class ResponseAccumulator {
    private val text = StringBuilder()
    private val toolCalls = LinkedHashMap<String, ToolCall>()
    private var finishReason: String? = null

    /** The latest usage a fragment reported ([TokenUsage.NONE] until one does). */
    var usage = TokenUsage.NONE
        private set

    /** Adds [response] and returns its new text (empty if none). */
    fun add(response: ChatResponse): String {
        val result = response.result
        val delta = result?.output?.text.orEmpty()
        text.append(delta)
        result?.output?.toolCalls.orEmpty().forEach { call ->
            toolCalls[call.id()] = ToolCall(call.id(), call.name(), call.arguments().ifBlank { EMPTY_ARGUMENTS })
        }
        result
            ?.metadata
            ?.finishReason
            ?.takeIf { it.isNotBlank() }
            ?.let { finishReason = it }
        tokenUsage(response.metadata.usage)?.let { usage = it }
        return delta
    }

    fun toResponse(): LlmResponse {
        val calls = toolCalls.values.toList()
        return LlmResponse(text.toString(), calls, finishReasonOf(finishReason, calls.isNotEmpty()), usage)
    }

    private fun tokenUsage(reported: Usage?): TokenUsage? {
        val input = reported?.promptTokens?.toLong() ?: 0
        val output = reported?.completionTokens?.toLong() ?: 0
        return if (input + output > 0) TokenUsage(input, output) else null
    }

    companion object {
        private const val EMPTY_ARGUMENTS = "{}"

        fun of(response: ChatResponse): LlmResponse = ResponseAccumulator().apply { add(response) }.toResponse()

        /** Provider stop reasons (OpenAI, Anthropic and the OpenAI-compatible APIs) in one vocabulary. */
        fun finishReasonOf(
            reported: String?,
            hasToolCalls: Boolean,
        ): FinishReason {
            if (hasToolCalls) return FinishReason.TOOL_CALLS
            return when (reported?.lowercase(Locale.ROOT)) {
                "length", "max_tokens", "model_context_window_exceeded" -> FinishReason.MAX_TOKENS
                "content_filter", "refusal" -> FinishReason.CONTENT_FILTERED
                else -> FinishReason.STOP
            }
        }
    }
}
