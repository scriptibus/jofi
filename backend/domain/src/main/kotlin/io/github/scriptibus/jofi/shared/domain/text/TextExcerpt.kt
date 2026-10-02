// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.text

/**
 * The start of a long text for a list (ADR-0056): at most [MAX_LENGTH] code points, never cut inside a surrogate
 * pair, and [truncated] says whether anything was left out. The full text comes from reading the one entity.
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
            return TextExcerpt(full.substring(0, full.offsetByCodePoints(0, maxLength)), true)
        }

        /** The excerpt of an optional text; `null` if there is none. */
        fun ofOrNull(full: String?): TextExcerpt? = full?.let { of(it) }
    }
}
