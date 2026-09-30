// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/** Whether a stored item may reach an AI provider (spec §4.1). */
enum class AiVisibility {
    SENDABLE,
    NEVER_SEND,
}

/**
 * A text that must never reach an AI provider, taken from an item flagged "never send to AI" (for
 * example an address line or a phone number). The filter redacts it wherever it appears, also in
 * content that carries no source. [toString] hides it (threat model T4).
 */
data class FlaggedValue(
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "A flagged value must not be blank" }
    }

    override fun toString(): String = "FlaggedValue(chars=${text.length})"
}

/**
 * What the "never send to AI" source answered for one AI call: a [verdicts] entry for each source
 * the request carries, and every [flaggedValues] entry of every flagged item. A source without a
 * verdict is undecided, and the call is refused (fail closed). [toString] shows sizes only.
 */
data class NeverSendRules(
    val verdicts: Map<ContentSource, AiVisibility>,
    val flaggedValues: Set<FlaggedValue>,
) {
    override fun toString(): String = "NeverSendRules(verdicts=${verdicts.size}, flaggedValues=${flaggedValues.size})"

    companion object {
        /** Nothing is flagged and no source is known. */
        val NONE = NeverSendRules(emptyMap(), emptySet())
    }
}

/** The answer of the "never send to AI" source; [Unavailable] makes the gateway refuse the call. */
sealed interface AiVisibilityResult {
    data class Known(
        val rules: NeverSendRules,
    ) : AiVisibilityResult

    /** The source could not answer (storage failure, index not ready). Carries no content. */
    data class Unavailable(
        val reason: String,
    ) : AiVisibilityResult
}
