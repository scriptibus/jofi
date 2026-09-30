// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

import java.text.Normalizer

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
 * every piece of text a request would send: system prompts, user messages, earlier answers and their
 * tool call arguments, tool results and embedding inputs. Tool definitions are Jofi's own code and
 * are not filtered.
 *
 * - A [ContentPart.Sourced] part whose source is flagged is replaced by [REDACTION]; an embedding
 *   input from a flagged source withholds the whole request.
 * - A source without a verdict makes the whole request [FilterOutcome.Undecided] (fail closed).
 * - Every [FlaggedValue] is redacted wherever it appears, also in text without a source (what the
 *   user typed, a tool result built from strings, an earlier answer). Matching ignores case and
 *   treats any run of whitespace as equal, after Unicode NFC normalisation.
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
        val redactor = Redactor(rules)
        val messages = request.messages.map { redactor.message(it) }
        return FilterOutcome.Passed(request.copy(messages = messages), redactor.redactions)
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
                val redactor = Redactor(rules)
                val inputs = request.inputs.map { ContentPart.Plain(redactor.text(it.text)) }
                FilterOutcome.Passed(request.copy(inputs = inputs), redactor.redactions)
            }
        }
    }

    private fun partsOf(message: LlmMessage): List<ContentPart> =
        when (message) {
            is LlmMessage.System -> message.parts
            is LlmMessage.User -> message.parts
            is LlmMessage.ToolResult -> message.parts
            is LlmMessage.Assistant -> emptyList()
        }

    /** Rewrites text for one request and counts what it withheld. */
    private class Redactor(
        private val rules: NeverSendRules,
    ) {
        // Longest first, so a value that contains a shorter one is withheld as a whole.
        private val patterns: List<Regex> =
            rules.flaggedValues
                .map { normalized(it.text).trim() }
                .distinct()
                .sortedByDescending { it.length }
                .map(::patternOf)

        var redactions = 0
            private set

        fun message(message: LlmMessage): LlmMessage =
            when (message) {
                is LlmMessage.System -> LlmMessage.System(parts(message.parts))
                is LlmMessage.User -> LlmMessage.User(parts(message.parts))
                is LlmMessage.ToolResult -> LlmMessage.ToolResult(message.toolCallId, parts(message.parts))
                is LlmMessage.Assistant -> assistant(message)
            }

        private fun assistant(message: LlmMessage.Assistant): LlmMessage.Assistant =
            LlmMessage.Assistant(
                text(message.text),
                message.toolCalls.map { it.copy(arguments = text(it.arguments)) },
            )

        private fun parts(parts: List<ContentPart>): List<ContentPart> = parts.map(::part)

        private fun part(part: ContentPart): ContentPart =
            when (part) {
                is ContentPart.Plain -> {
                    ContentPart.Plain(text(part.text))
                }

                is ContentPart.Sourced -> {
                    if (rules.verdicts[part.source] == AiVisibility.SENDABLE) {
                        ContentPart.Plain(text(part.text))
                    } else {
                        redactions++
                        ContentPart.Plain(REDACTION)
                    }
                }
            }

        fun text(original: String): String {
            if (patterns.isEmpty()) return original
            val normalized = normalized(original)
            var result = normalized
            for (pattern in patterns) {
                result = pattern.replace(result) { _ -> REDACTION.also { redactions++ } }
            }
            return if (result == normalized) original else result
        }

        private fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

        private fun patternOf(value: String): Regex =
            Regex(
                value.split(WHITESPACE).joinToString(WHITESPACE.pattern) { Regex.escape(it) },
                RegexOption.IGNORE_CASE,
            )

        private companion object {
            val WHITESPACE = Regex("\\s+")
        }
    }
}
