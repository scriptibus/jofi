// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

import java.text.Normalizer

/** A text after redaction and how many pieces were replaced by [NeverSendFilter.REDACTION]. */
internal data class Redacted(
    val text: String,
    val count: Int,
)

/**
 * Finds every [FlaggedValue] in a text and replaces it (ADR-0043). Texts and values are compared
 * after Unicode NFKC normalisation (full-width digits, no-break and thin spaces become plain ones),
 * ignoring case. Invisible format characters (`\p{Cf}`: zero-width space and joiner, BOM, ...) may
 * sit between any two characters; any run of whitespace or separators (`[\s\p{Z}]`) stands for the
 * whitespace in a value. In a value made mostly of digits (a phone or account number), any of
 * `[\s\p{Z}\-/.()]` may sit between its characters, so `0170-1234567`, `0170/1234567` and
 * `(0)170 1234567` all match `0170 1234567`. A value shorter than [SHORT_VALUE] characters only
 * matches as a whole word. All matches of all values are collected on the normalised text, merged
 * where they overlap or touch, and each merged range is replaced once: a replacement is never
 * scanned again.
 */
internal class ValueRedactor(
    values: Set<FlaggedValue>,
) {
    private val patterns: List<Regex> = values.mapNotNull { patternOf(it.text) }

    /** [original] redacted; unchanged (not even normalised) when nothing matches. */
    fun text(original: String): Redacted = redact(normalized(original), emptyList(), original)

    /**
     * Redacts [normalizedText] (already NFKC-normalised by the caller), where [withheld] ranges are
     * already [NeverSendFilter.REDACTION] markers; they merge with overlapping matches. Returns
     * [original] when there is neither a match nor a marker.
     */
    fun redact(
        normalizedText: String,
        withheld: List<IntRange>,
        original: String,
    ): Redacted {
        val matches = patterns.flatMap { pattern -> pattern.findAll(normalizedText).map { it.range } }
        // A marker a value matches inside of (a value `held`) is one piece with it, not a marker to nest in a marker.
        val touched =
            MARKER.findAll(normalizedText).map { it.range }.filter { marker ->
                matches.any { overlaps(marker, it) }
            }
        val ranges = merge(withheld + matches + touched)
        if (ranges.isEmpty()) return Redacted(original, 0)
        val result = StringBuilder()
        var next = 0
        for (range in ranges) {
            result.append(normalizedText, next, range.first).append(NeverSendFilter.REDACTION)
            next = range.last + 1
        }
        result.append(normalizedText, next, normalizedText.length)
        return Redacted(result.toString(), ranges.size)
    }

    companion object {
        /** Values shorter than this (without separators) only match as whole words. */
        const val SHORT_VALUE = 4

        private val MARKER = Regex(Regex.escape(NeverSendFilter.REDACTION))

        private fun overlaps(
            a: IntRange,
            b: IntRange,
        ): Boolean = a.first <= b.last && b.first <= a.last

        private const val FORMAT = "\\p{Cf}*"
        private const val WORD_GAP = "[\\s\\p{Z}\\p{Cf}]+"
        private const val DIGIT_GAP = "[\\s\\p{Z}\\p{Cf}\\-/.()]*"
        private const val NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
        private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}])"
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")
        private val DIGIT_SEPARATOR = Regex("[\\s\\p{Z}\\-/.()]")

        fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

        private fun isFormat(codePoint: Int): Boolean = Character.getType(codePoint) == Character.FORMAT.toInt()

        private fun withoutFormat(text: String): String =
            buildString { text.codePoints().filter { !isFormat(it) }.forEach { appendCodePoint(it) } }

        private fun escaped(text: String): List<String> =
            text.codePoints().toArray().map { Regex.escape(String(Character.toChars(it))) }

        private fun patternOf(value: String): Regex? {
            val clean = withoutFormat(normalized(value)).trim()
            val compact = clean.replace(DIGIT_SEPARATOR, "")
            if (compact.isEmpty()) return null
            val digitHeavy = compact.count(Char::isDigit) * 2 >= compact.length
            val body =
                if (digitHeavy) {
                    escaped(compact).joinToString(DIGIT_GAP)
                } else {
                    clean.split(WHITESPACE).joinToString(WORD_GAP) { escaped(it).joinToString(FORMAT) }
                }
            val bounded = if (compact.length < SHORT_VALUE) NOT_WORD_BEFORE + body + NOT_WORD_AFTER else body
            return Regex(bounded, RegexOption.IGNORE_CASE)
        }

        /** Sorted, with overlapping and touching ranges merged. */
        private fun merge(ranges: List<IntRange>): List<IntRange> {
            val merged = mutableListOf<IntRange>()
            for (range in ranges.filterNot(IntRange::isEmpty).sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && range.first <= last.last + 1) {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
                } else {
                    merged += range
                }
            }
            return merged
        }
    }
}
