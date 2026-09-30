// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/** One turn of a conversation with a language model, provider-neutral. */
sealed interface LlmMessage {
    /** Instructions from Jofi itself. Untrusted content (postings, pages) never goes here. */
    data class System(
        val text: String,
    ) : LlmMessage {
        init {
            require(text.isNotBlank()) { "A system message must not be blank" }
        }
    }

    /** What the user (or content on the user's behalf) says to the model. */
    data class User(
        val text: String,
    ) : LlmMessage {
        init {
            require(text.isNotBlank()) { "A user message must not be blank" }
        }
    }

    /** An earlier model answer: text, tool calls or both. */
    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall> = emptyList(),
    ) : LlmMessage {
        init {
            require(text.isNotBlank() || toolCalls.isNotEmpty()) { "An assistant message needs text or tool calls" }
        }
    }

    /** The result of running the tool call [toolCallId], returned to the model as data. */
    data class ToolResult(
        val toolCallId: String,
        val content: String,
    ) : LlmMessage {
        init {
            require(toolCallId.isNotBlank()) { "A tool result must name its tool call" }
        }
    }
}

/**
 * A tool the model may call. [inputSchema] is the JSON Schema of the arguments as text; the AI
 * adapter passes it to the provider unchanged.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String,
) {
    init {
        require(NAME_PATTERN.matches(name)) { "A tool name must match $NAME_PATTERN" }
        require(description.isNotBlank()) { "A tool description must not be blank" }
        require(inputSchema.isNotBlank()) { "A tool input schema must not be blank" }
    }

    private companion object {
        /** The strictest rule shared by the supported providers. */
        val NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/** The model asks Jofi to run tool [name] with [arguments] (JSON text); [id] links the result. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    init {
        require(id.isNotBlank()) { "A tool call id must not be blank" }
        require(name.isNotBlank()) { "A tool call must name its tool" }
    }
}
