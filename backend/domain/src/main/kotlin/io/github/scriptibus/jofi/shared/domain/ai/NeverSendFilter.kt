// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/** What the "never send to AI" filter made of a request. */
sealed interface FilterOutcome<out T> {
    /** [value] may be sent; [redactions] pieces were replaced by [NeverSendFilter.REDACTION]. */
    data class Passed<out T>(
        val value: T,
        val redactions: Int,
    ) : FilterOutcome<T>

    /** [sources] items the request quotes had no verdict; nothing may be sent (fail closed). */
    data class Undecided(
        val sources: Int,
    ) : FilterOutcome<Nothing>

    /** An embedding input comes from a flagged item; nothing may be sent. */
    data object Withheld : FilterOutcome<Nothing>
}

/**
 * Domain service: the "never send to AI" filter (spec §4.1, threat model T3, ADR-0043). It runs on
 * everything a request would put on the wire: system prompts, user messages, earlier answers and
 * their tool call arguments, tool results, tool definitions and embedding inputs.
 *
 * 1. Sources: a [ContentPart.Sourced] part whose source is flagged becomes [REDACTION]; an embedding
 *    input from a flagged source withholds the whole request. A source without a verdict makes the
 *    whole request [FilterOutcome.Undecided] (fail closed).
 * 2. Values: every [FlaggedValue] is redacted from the text each message sends, i.e. its parts
 *    joined as the provider adapter joins them (so a value split across parts is found), and from
 *    embedding inputs and tool definitions ([ValueRedactor] explains the matching). Tool call
 *    arguments and tool schemas are JSON: values are redacted inside their string values, so the
 *    JSON stays valid ([JsonStrings]).
 *
 * The result carries only plain parts: the provider adapter never sees a source.
 */
object NeverSendFilter {
    /** What a withheld piece of text is replaced by, so the model sees that something is missing. */
    const val REDACTION = "[withheld]"

    fun sourcesOf(request: LlmRequest): Set<ContentSource> =
        request.messages
            .flatMap(::partsOf)
            .filterIsInstance<ContentPart.Sourced>()
            .map { it.source }
            .toSet()

    fun sourcesOf(request: EmbeddingRequest): Set<ContentSource> =
        request.inputs
            .filterIsInstance<ContentPart.Sourced>()
            .map { it.source }
            .toSet()

    fun apply(
        request: LlmRequest,
        rules: NeverSendRules,
    ): FilterOutcome<LlmRequest> {
        val undecided = sourcesOf(request).count { it !in rules.verdicts }
        if (undecided > 0) return FilterOutcome.Undecided(undecided)
        val redaction = Redaction(rules)
        val messages = request.messages.map(redaction::message)
        val tools = request.tools.map(redaction::tool)
        return FilterOutcome.Passed(request.copy(messages = messages, tools = tools), redaction.count)
    }

    fun apply(
        request: EmbeddingRequest,
        rules: NeverSendRules,
    ): FilterOutcome<EmbeddingRequest> {
        val sources = sourcesOf(request)
        val undecided = sources.count { it !in rules.verdicts }
        return when {
            undecided > 0 -> {
                FilterOutcome.Undecided(undecided)
            }

            sources.any { rules.verdicts[it] == AiVisibility.NEVER_SEND } -> {
                FilterOutcome.Withheld
            }

            else -> {
                val redaction = Redaction(rules)
                val inputs = request.inputs.map { ContentPart.Plain(redaction.plain(it.text)) }
                FilterOutcome.Passed(request.copy(inputs = inputs), redaction.count)
            }
        }
    }

    /**
     * The result of an MCP tool (JSON) as it may leave Jofi (#116): every flagged value is redacted inside
     * its string values (and numbers), so the JSON stays valid. Tools that return flaggable items (knowledge,
     * M2) leave flagged ones out before serialising; this is the value scan behind that.
     */
    fun applyToToolResult(
        json: String,
        rules: NeverSendRules,
    ): FilterOutcome.Passed<String> {
        val redacted = JsonStrings.redact(json, ValueRedactor(rules.flaggedValues))
        return FilterOutcome.Passed(redacted.text, redacted.count)
    }

    private fun partsOf(message: LlmMessage): List<ContentPart> =
        when (message) {
            is LlmMessage.System -> message.parts
            is LlmMessage.User -> message.parts
            is LlmMessage.ToolResult -> message.parts
            is LlmMessage.Assistant -> emptyList()
        }

    /** Rewrites the text of one request and counts what it withheld. */
    private class Redaction(
        private val rules: NeverSendRules,
    ) {
        private val values = ValueRedactor(rules.flaggedValues)

        var count = 0
            private set

        fun message(message: LlmMessage): LlmMessage =
            when (message) {
                is LlmMessage.System -> LlmMessage.System(joined(message.parts))
                is LlmMessage.User -> LlmMessage.User(joined(message.parts))
                is LlmMessage.ToolResult -> LlmMessage.ToolResult(message.toolCallId, joined(message.parts))
                is LlmMessage.Assistant -> assistant(message)
            }

        fun tool(tool: ToolDefinition): ToolDefinition =
            ToolDefinition(tool.name, plain(tool.description), json(tool.inputSchema))

        fun plain(text: String): String = counted(values.text(text))

        private fun json(text: String): String = counted(JsonStrings.redact(text, values))

        private fun assistant(message: LlmMessage.Assistant): LlmMessage.Assistant =
            LlmMessage.Assistant(
                plain(message.text),
                message.toolCalls.map { it.copy(arguments = json(it.arguments)) },
            )

        /** The parts as the provider receives them (joined), flagged sources withheld, then scanned. */
        private fun joined(parts: List<ContentPart>): String {
            val normalized = StringBuilder()
            val withheld = mutableListOf<IntRange>()
            for (part in parts) {
                if (part is ContentPart.Sourced && rules.verdicts[part.source] != AiVisibility.SENDABLE) {
                    withheld += normalized.length until normalized.length + REDACTION.length
                    normalized.append(REDACTION)
                } else {
                    normalized.append(ValueRedactor.normalized(part.text))
                }
            }
            return counted(values.redact(normalized.toString(), withheld, ContentPart.join(parts)))
        }

        private fun counted(redacted: Redacted): String {
            count += redacted.count
            return redacted.text
        }
    }
}
