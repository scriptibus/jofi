// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.text

import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter

/**
 * The start of a long text for a list (ADR-0056): at most [MAX_LENGTH] code points, never cut inside a surrogate
 * pair or inside a redaction marker, and [truncated] says whether anything was left out. The full text comes from
 * reading the one entity.
 */
data class TextExcerpt(
    val text: String,
    val truncated: Boolean,
) {
    companion object {
        /** Enough to recognise a note in a list; 50 entries then stay far below a model's context. */
        const val MAX_LENGTH = 300

        fun of(
            full: String,
            maxLength: Int = MAX_LENGTH,
        ): TextExcerpt {
            require(maxLength >= 0) { "An excerpt has a non-negative length" }
            if (full.codePointCount(0, full.length) <= maxLength) return TextExcerpt(full, false)
            val end = beforeMarker(full, full.offsetByCodePoints(0, maxLength))
            return TextExcerpt(full.substring(0, end), true)
        }

        /**
         * Where to cut: at [end], or before the "never send to AI" marker that [end] would cut in two, so an excerpt
         * never shows half of it (a model is told what the whole marker means).
         */
        private fun beforeMarker(
            full: String,
            end: Int,
        ): Int {
            val start = full.lastIndexOf(NeverSendFilter.REDACTION, end - 1)
            return if (start >= 0 && start + NeverSendFilter.REDACTION.length > end) start else end
        }

        /** The excerpt of an optional text; `null` if there is none. */
        fun ofOrNull(full: String?): TextExcerpt? = full?.let { of(it) }
    }
}
