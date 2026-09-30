// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/**
 * One call to a language model for [task]: the conversation so far, the tools the model may call
 * and an optional output limit. Provider and model are not part of the request; they are resolved
 * from the task's model assignment, so callers never pick a provider.
 *
 * [outputSchema] asks for structured output: a JSON Schema (as text) the answer must follow, which the
 * provider adapter passes on as the provider's native response format. Like tool definitions it is
 * Jofi's own code, never user data. The answer is still untrusted: callers parse and validate it.
 */
data class LlmRequest(
    val task: AiTask,
    val messages: List<LlmMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val maxOutputTokens: Int? = null,
    val outputSchema: String? = null,
) {
    init {
        require(task.kind == AiTaskKind.TEXT_GENERATION) { "Task $task is not a text-generation task" }
        require(messages.isNotEmpty()) { "A request needs at least one message" }
        require(tools.isEmpty() || task.toolsAllowed) { "Task $task must not get tools (threat model T2)" }
        require(tools.map { it.name }.toSet().size == tools.size) { "Tool names must be unique" }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "The output limit must be positive" }
        require(outputSchema == null || outputSchema.isNotBlank()) { "An output schema must not be blank" }
    }

    /** Roles and sizes only: the messages hold personal data (threat model T4). */
    override fun toString(): String =
        "LlmRequest(task=$task, messages=$messages, tools=${tools.map {
            it.name
        }}, maxOutputTokens=$maxOutputTokens, " +
            "structured=${outputSchema != null})"
}

/** The model's final answer: text, requested tool calls, why it stopped and what it cost. */
data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val finishReason: FinishReason,
    val usage: TokenUsage,
) {
    init {
        require(finishReason != FinishReason.TOOL_CALLS || toolCalls.isNotEmpty()) {
            "A response that stopped for tool calls must contain them"
        }
    }

    /** Sizes only: the answer can quote personal data (threat model T4). */
    override fun toString(): String =
        "LlmResponse(chars=${text.length}, toolCalls=$toolCalls, finishReason=$finishReason, usage=$usage)"
}

/** Why the model stopped generating. */
enum class FinishReason {
    /** Natural end of the answer. */
    STOP,

    /** The output limit was reached; the text is truncated. */
    MAX_TOKENS,

    /** The model wants Jofi to run tools before it continues. */
    TOOL_CALLS,

    /** The provider's safety filter cut the answer. */
    CONTENT_FILTERED,
}

/** Tokens a call consumed, as reported by the provider. The base for cost metering. */
data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
) {
    init {
        require(inputTokens >= 0) { "Input tokens must not be negative" }
        require(outputTokens >= 0) { "Output tokens must not be negative" }
    }

    val totalTokens: Long get() = inputTokens + outputTokens

    operator fun plus(other: TokenUsage): TokenUsage =
        TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)

    companion object {
        val NONE = TokenUsage(0, 0)
    }
}
