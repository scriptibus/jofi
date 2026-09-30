// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/** Whether a stored item may reach an AI provider (spec §4.1). */
enum class AiVisibility {
    SENDABLE,
    NEVER_SEND,
}

/**
 * A text that must never reach an AI provider, taken from an item flagged "never send to AI": the
 * item's full text, each of its lines and each of its fields (an address line, a phone number). The
 * filter redacts it wherever it appears, also in content that carries no source. A value shorter than
 * four letters or digits only matches as a whole word; when flags are created (M2), the user should be
 * warned that such short values are also withheld wherever they stand alone. [toString] hides it
 * (threat model T4).
 */
data class FlaggedValue(
    val text: String,
) {
    init {
        // A value of only invisible or separator characters would match everywhere.
        require(text.any(Char::isLetterOrDigit)) { "A flagged value needs at least one letter or digit" }
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
