// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.ToolDefinition
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition as SpringToolDefinition

/** Translates our provider-neutral conversation into Spring AI messages and tool declarations. */
internal object PromptMapper {
    fun messages(conversation: List<LlmMessage>): List<Message> {
        val calls = conversation.filterIsInstance<LlmMessage.Assistant>().flatMap { it.toolCalls }
        val toolNames = calls.associate { it.id to it.name }
        val result = mutableListOf<Message>()
        val pendingResults = mutableListOf<ToolResponseMessage.ToolResponse>()
        for (message in conversation) {
            if (message is LlmMessage.ToolResult) {
                pendingResults +=
                    ToolResponseMessage.ToolResponse(
                        message.toolCallId,
                        toolNames[message.toolCallId] ?: message.toolCallId,
                        message.content,
                    )
                continue
            }
            flushToolResults(pendingResults, result)
            result += toMessage(message)
        }
        flushToolResults(pendingResults, result)
        return result
    }

    /** Declarations only: the model returns tool calls and Jofi runs the tools itself (T2). */
    fun toolCallbacks(tools: List<ToolDefinition>): List<ToolCallback> = tools.map(::DeclaredTool)

    /** Consecutive tool results go back as one message, as Anthropic expects them in one turn. */
    private fun flushToolResults(
        pending: MutableList<ToolResponseMessage.ToolResponse>,
        into: MutableList<Message>,
    ) {
        if (pending.isEmpty()) return
        into += ToolResponseMessage.builder().responses(pending.toList()).build()
        pending.clear()
    }

    private fun toMessage(message: LlmMessage): Message =
        when (message) {
            is LlmMessage.System -> {
                SystemMessage(message.text)
            }

            is LlmMessage.User -> {
                UserMessage(message.text)
            }

            is LlmMessage.Assistant -> {
                AssistantMessage
                    .builder()
                    .content(message.text)
                    .toolCalls(
                        message.toolCalls.map { AssistantMessage.ToolCall(it.id, FUNCTION, it.name, it.arguments) },
                    ).build()
            }

            is LlmMessage.ToolResult -> {
                error("Tool results are grouped by messages()")
            }
        }

    private const val FUNCTION = "function"

    private class DeclaredTool(
        tool: ToolDefinition,
    ) : ToolCallback {
        private val definition =
            SpringToolDefinition
                .builder()
                .name(tool.name)
                .description(tool.description)
                .inputSchema(tool.inputSchema)
                .build()

        override fun getToolDefinition(): SpringToolDefinition = definition

        override fun call(toolInput: String): String =
            throw UnsupportedOperationException("Jofi runs tools itself; Spring AI never executes them")
    }
}
