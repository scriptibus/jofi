// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/**
 * The kinds of stored items whose content can be marked "never send to AI" (spec §4.1). Only
 * knowledge entries can be flagged today; a new kind needs a source that answers for it
 * (`AiVisibilityPort`), otherwise every request quoting it is refused (fail closed).
 */
enum class ContentSourceType {
    KNOWLEDGE_ENTRY,
}

/**
 * The stored item a piece of prompt content was taken from: the shared marker of AI visibility
 * (ADR-0043). The AI gateway asks `AiVisibilityPort` about every source a request carries before
 * any provider call.
 */
data class ContentSource(
    val type: ContentSourceType,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "A content source needs an id" }
    }
}

/**
 * One piece of the text of a message or an embedding input. Callers mark every piece they take
 * from a stored item as [Sourced], so the "never send to AI" filter can withhold it by its source,
 * not only by its words. [toString] shows sizes only (threat model T4).
 */
sealed interface ContentPart {
    val text: String

    /** Text Jofi wrote itself, or content without a stored source (a posting, what the user typed). */
    data class Plain(
        override val text: String,
    ) : ContentPart {
        override fun toString(): String = "Plain(chars=${text.length})"
    }

    /** Text taken from the stored item [source]. */
    data class Sourced(
        override val text: String,
        val source: ContentSource,
    ) : ContentPart {
        override fun toString(): String = "Sourced(source=$source, chars=${text.length})"
    }

    companion object {
        /** The parts' text as one string, in order. */
        fun join(parts: List<ContentPart>): String = parts.joinToString("") { it.text }
    }
}
