// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

/**
 * Best-effort plain text from a fetched HTML job posting (spec §8.1, #97): drops script, style and other
 * non-content elements, turns block boundaries into line breaks, strips the remaining markup, decodes entities and
 * collapses whitespace. The result is as untrusted as the page itself, never instructions.
 */
object PostingHtmlText {
    fun extract(html: String): String {
        val withoutNoise = NOISE_ELEMENTS.fold(html, ::removeElement)
        val withoutComments = COMMENT.replace(withoutNoise, " ")
        val withBreaks = BLOCK_BOUNDARY.replace(withoutComments, "\n")
        return collapse(decodeEntities(TAG.replace(withBreaks, "")))
    }

    private fun removeElement(
        html: String,
        tag: String,
    ): String = Regex("<$tag\\b[^>]*>.*?</$tag\\s*>", ELEMENT_OPTIONS).replace(html, " ")

    private fun decodeEntities(text: String): String {
        val named = NAMED_ENTITIES.entries.fold(text) { decoded, (name, value) -> decoded.replace("&$name;", value) }
        val hex =
            HEX_ENTITY.replace(named) {
                it.groupValues[1]
                    .toInt(HEX_RADIX)
                    .toChar()
                    .toString()
            }
        return NUMERIC_ENTITY.replace(hex) { match ->
            match.groupValues[1]
                .toIntOrNull()
                ?.toChar()
                ?.toString()
                ?: match.value
        }
    }

    private fun collapse(text: String): String {
        val trimmedLines = text.lineSequence().joinToString("\n") { it.trim() }
        return BLANK_LINES.replace(trimmedLines, "\n\n").trim()
    }

    private const val HEX_RADIX = 16
    private val ELEMENT_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val NOISE_ELEMENTS = setOf("script", "style", "head", "noscript", "template", "svg")
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_BOUNDARY =
        Regex(
            "</?(?:p|div|li|ul|ol|br|h[1-6]|tr|table|section|article|header|footer|nav)\\b[^>]*>",
            RegexOption.IGNORE_CASE,
        )
    private val TAG = Regex("<[^>]*>")
    private val BLANK_LINES = Regex("\n{3,}")
    private val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")
    private val NUMERIC_ENTITY = Regex("&#(\\d+);")
    private val NAMED_ENTITIES =
        mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
}
