// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/**
 * One turn of a conversation with a language model, provider-neutral. Messages carry personal data
 * (CVs, postings, chat), so [toString] shows the role and sizes only, never the content (threat
 * model T4): a message that slips into a log line or an exception stays unreadable.
 *
 * Text that Jofi takes from a stored item goes in as a [ContentPart.Sourced] part, so the AI gateway
 * can withhold it when the item is flagged "never send to AI" (spec §4.1, ADR-0043).
 */
sealed interface LlmMessage {
    /** Instructions from Jofi itself. Untrusted content (postings, pages) never goes here. */
    data class System(
        val parts: List<ContentPart>,
    ) : LlmMessage {
        constructor(text: String) : this(listOf(ContentPart.Plain(text)))

        val text: String get() = ContentPart.join(parts)

        init {
            require(text.isNotBlank()) { "A system message must not be blank" }
        }

        override fun toString(): String = "System(chars=${text.length}, parts=${parts.size})"
    }

    /** What the user (or content on the user's behalf) says to the model. */
    data class User(
        val parts: List<ContentPart>,
    ) : LlmMessage {
        constructor(text: String) : this(listOf(ContentPart.Plain(text)))

        val text: String get() = ContentPart.join(parts)

        init {
            require(text.isNotBlank()) { "A user message must not be blank" }
        }

        override fun toString(): String = "User(chars=${text.length}, parts=${parts.size})"
    }

    /** An earlier model answer: text, tool calls or both. */
    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall> = emptyList(),
    ) : LlmMessage {
        init {
            require(text.isNotBlank() || toolCalls.isNotEmpty()) { "An assistant message needs text or tool calls" }
        }

        override fun toString(): String = "Assistant(chars=${text.length}, toolCalls=$toolCalls)"
    }

    /** The result of running the tool call [toolCallId], returned to the model as data. */
    data class ToolResult(
        val toolCallId: String,
        val parts: List<ContentPart>,
    ) : LlmMessage {
        constructor(toolCallId: String, content: String) : this(toolCallId, listOf(ContentPart.Plain(content)))

        val content: String get() = ContentPart.join(parts)

        init {
            require(toolCallId.isNotBlank()) { "A tool result must name its tool call" }
        }

        override fun toString(): String =
            "ToolResult(toolCallId=$toolCallId, chars=${content.length}, parts=${parts.size})"
    }
}

/**
 * A tool the model may call. [inputSchema] is the JSON Schema of the arguments as text; the AI
 * adapter passes it to the provider unchanged. Definitions are Jofi's own code, not user data.
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

/**
 * The model asks Jofi to run tool [name] with [arguments] (JSON text); [id] links the result.
 * [toString] hides the arguments, which can quote personal data.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    init {
        require(id.isNotBlank()) { "A tool call id must not be blank" }
        require(name.isNotBlank()) { "A tool call must name its tool" }
    }

    override fun toString(): String = "ToolCall(id=$id, name=$name, argumentChars=${arguments.length})"
}
