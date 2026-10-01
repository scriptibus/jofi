// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import java.nio.charset.Charset

/**
 * Best-effort plain text from a fetched HTML job posting (spec §8.1, #97): drops script, style and other
 * non-content elements and comments, turns block boundaries into line breaks, strips the remaining tags, decodes
 * entities once and collapses whitespace. The result is as untrusted as the page itself, never instructions.
 *
 * One forward pass over the page: every character is looked at a bounded number of times, never rescanned from an
 * earlier position, so hostile markup (millions of `<`, unclosed comments, scripts or quotes) costs time linear in
 * its size. Anything unterminated swallows the rest of the page instead of backtracking.
 */
object PostingHtmlText {
    fun extract(html: String): String = TextScanner(html).scan()

    private const val COMMENT_OPEN_LENGTH = 4
    private const val COMMENT_CLOSE_LENGTH = 3

    /** Elements whose content is no posting text; not `head`, which may omit its end tag. */
    private val NOISE_ELEMENTS = setOf("script", "style", "title", "noscript", "template", "svg")

    /** The only noise element that may close itself (`<svg/>`); `<script src=x/>` still opens a script. */
    private const val SELF_CLOSING_NOISE = "svg"
    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"
    private val SPACE_RUNS = Regex("[ \t]{2,}")
    private val BLOCK_ELEMENTS =
        setOf("p", "div", "li", "ul", "ol", "br", "tr", "table", "section", "article", "header", "footer", "nav") +
            (1..6).map { "h$it" }
    private val BLANK_LINES = Regex("\\n{3,}")

    private class TextScanner(
        private val html: String,
    ) {
        private val text = StringBuilder()
        private var position = 0

        fun scan(): String {
            while (position < html.length) {
                when (html[position]) {
                    '<' -> markup()
                    '&' -> entity()
                    else -> append(html[position++])
                }
            }
            return collapse(text.toString())
        }

        private fun markup() {
            val next = html.getOrNull(position + 1)
            when {
                html.startsWith("<!--", position) -> comment()
                html.startsWith(CDATA_OPEN, position) -> cdata()
                next == '/' || next?.let(HtmlEntities::isAsciiLetter) == true -> tag()
                next == '!' || next == '?' -> position = indexAfter('>', position)
                else -> append(html[position++])
            }
        }

        private fun comment() {
            val close = html.indexOf("-->", position + COMMENT_OPEN_LENGTH)
            position = if (close < 0) html.length else close + COMMENT_CLOSE_LENGTH
            text.append(' ')
        }

        /** The text of a CDATA section, as XHTML pages use it; unterminated, the rest of the page. */
        private fun cdata() {
            val start = position + CDATA_OPEN.length
            val close = html.indexOf(CDATA_CLOSE, start)
            val end = if (close < 0) html.length else close
            html.substring(start, end).forEach(::append)
            position = if (close < 0) html.length else close + CDATA_CLOSE.length
        }

        private fun tag() {
            val closing = html[position + 1] == '/'
            val nameStart = position + if (closing) 2 else 1
            var nameEnd = nameStart
            while (nameEnd < html.length && HtmlEntities.isAsciiLetterOrDigit(html[nameEnd])) nameEnd++
            val name = html.substring(nameStart, nameEnd).lowercase()
            position = tagEnd(nameEnd)
            val selfClosing = name == SELF_CLOSING_NOISE && html.getOrNull(position - 2) == '/'
            when {
                !closing && !selfClosing && name in NOISE_ELEMENTS -> skipElementContent(name)
                name in BLOCK_ELEMENTS -> text.append('\n')
            }
        }

        /** Just after the `>` that ends the tag, skipping quoted attribute values; the end of the page if unclosed. */
        private fun tagEnd(from: Int): Int {
            var index = from
            while (index < html.length) {
                when (html[index]) {
                    '>' -> return index + 1
                    '=' -> index = afterQuotedValue(index + 1)
                    else -> index++
                }
            }
            return html.length
        }

        private fun afterQuotedValue(valueStart: Int): Int {
            var quote = valueStart
            while (quote < html.length && html[quote].isWhitespace()) quote++
            val mark = html.getOrNull(quote)
            if (mark != '"' && mark != '\'') return valueStart
            val close = html.indexOf(mark, quote + 1)
            return if (close < 0) html.length else close + 1
        }

        private fun skipElementContent(name: String) {
            val closer = "</$name"
            var index = position
            while (true) {
                index = html.indexOf('<', index)
                if (index < 0) {
                    position = html.length
                    return
                }
                if (closesElement(html, index, closer)) {
                    position = tagEnd(index + closer.length)
                    return
                }
                index++
            }
        }

        private fun indexAfter(
            char: Char,
            from: Int,
        ): Int = html.indexOf(char, from).let { if (it < 0) html.length else it + 1 }

        private fun entity() {
            val limit = minOf(html.length, position + 1 + HtmlEntities.MAX_LENGTH)
            var end = position + 1
            while (end < limit && html[end] != ';') end++
            val decoded = if (end < limit) HtmlEntities.decode(html.substring(position + 1, end)) else null
            if (decoded == null) {
                text.append('&')
                position++
            } else {
                decoded.forEach(::append)
                position = end + 1
            }
        }

        private fun append(char: Char) {
            if (!HtmlEntities.isControl(char.code)) text.append(char)
        }
    }

    /** Whether the `</name` at [index] ends exactly that element: `</header` does not end `head`. */
    private fun closesElement(
        html: String,
        index: Int,
        closer: String,
    ): Boolean {
        val after = html.getOrNull(index + closer.length)
        return html.regionMatches(index, closer, 0, closer.length, ignoreCase = true) &&
            (after == null || after == '/' || after == '>' || after.isWhitespace())
    }

    private fun collapse(text: String): String {
        val trimmedLines = text.lineSequence().joinToString("\n") { SPACE_RUNS.replace(it.trim(), " ") }
        return BLANK_LINES.replace(trimmedLines, "\n\n").trim()
    }
}

/** The entities German postings use (the HTML 4 Latin-1 set and common punctuation) and character classes. */
private object HtmlEntities {
    /** Longest entity body looked for after an `&`, so a run of `&` costs a bounded look-ahead each. */
    const val MAX_LENGTH = 32
    private const val HEX_RADIX = 16
    private val WINDOWS_1252_RANGE = 0x80..0x9F
    private val WINDOWS_1252 = Charset.forName("windows-1252")
    private const val REPLACEMENT = "\uFFFD"
    private const val LATIN1_FIRST = 0xA0
    private val SURROGATES = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
    private val LATIN1_NAMES =
        (
            "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 " +
                "acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc " +
                "Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve " +
                "Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute " +
                "acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde " +
                "ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        ).split(' ')
    private val NAMED: Map<String, String> =
        LATIN1_NAMES.withIndex().associate { (index, name) ->
            name to String(Character.toChars(LATIN1_FIRST + index))
        } +
            mapOf(
                "amp" to "&",
                "lt" to "<",
                "gt" to ">",
                "quot" to "\"",
                "apos" to "'",
                "nbsp" to " ",
                "ndash" to "–",
                "mdash" to "—",
                "euro" to "€",
                "bull" to "•",
                "hellip" to "…",
                "lsquo" to "‘",
                "rsquo" to "’",
                "sbquo" to "‚",
                "ldquo" to "“",
                "rdquo" to "”",
                "bdquo" to "„",
                "trade" to "™",
            )

    /** The text of the entity named or numbered [body] (without `&` and `;`), or null if there is none. */
    fun decode(body: String): String? =
        when {
            body.startsWith("#x", ignoreCase = true) -> codePointText(body.drop(2).toIntOrNull(HEX_RADIX))
            body.startsWith("#") -> codePointText(body.drop(1).toIntOrNull())
            else -> NAMED[body]
        }

    /**
     * Null for a number that is no usable character, so an untrusted page cannot make the import fail. 128 to 159
     * mean the windows-1252 characters there, as in browsers (`&#150;` is an en dash).
     */
    private fun codePointText(codePoint: Int?): String? =
        if (codePoint in WINDOWS_1252_RANGE) windows1252(codePoint ?: 0) else usableCodePointText(codePoint)

    private fun windows1252(code: Int): String? =
        String(byteArrayOf(code.toByte()), WINDOWS_1252).takeIf { it != REPLACEMENT && !isControl(it.first().code) }

    private fun usableCodePointText(codePoint: Int?): String? =
        codePoint
            ?.takeIf { Character.isValidCodePoint(it) && it != 0 && it !in SURROGATES && !isControl(it) }
            ?.let { String(Character.toChars(it)) }

    /** Control characters other than line feed and tab, which never belong in a posting's text. */
    fun isControl(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) && codePoint != '\n'.code && codePoint != '\t'.code

    fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

    fun isAsciiLetterOrDigit(char: Char): Boolean = isAsciiLetter(char) || char in '0'..'9'
}
