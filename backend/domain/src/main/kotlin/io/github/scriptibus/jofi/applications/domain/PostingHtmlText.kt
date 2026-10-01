// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

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
    private val NOISE_ELEMENTS = setOf("script", "style", "head", "noscript", "template", "svg")
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

        private fun tag() {
            val closing = html[position + 1] == '/'
            val nameStart = position + if (closing) 2 else 1
            var nameEnd = nameStart
            while (nameEnd < html.length && HtmlEntities.isAsciiLetterOrDigit(html[nameEnd])) nameEnd++
            val name = html.substring(nameStart, nameEnd).lowercase()
            position = tagEnd(nameEnd)
            val selfClosing = html.getOrNull(position - 2) == '/'
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
                if (html.regionMatches(index, closer, 0, closer.length, ignoreCase = true)) {
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

    private fun collapse(text: String): String {
        val trimmedLines = text.lineSequence().joinToString("\n") { it.trim() }
        return BLANK_LINES.replace(trimmedLines, "\n\n").trim()
    }
}

/** The entities German postings use (the HTML 4 Latin-1 set and common punctuation) and character classes. */
private object HtmlEntities {
    /** Longest entity body looked for after an `&`, so a run of `&` costs a bounded look-ahead each. */
    const val MAX_LENGTH = 32
    private const val HEX_RADIX = 16
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

    /** Null for a number that is no usable character, so an untrusted page cannot make the import fail. */
    private fun codePointText(codePoint: Int?): String? =
        codePoint
            ?.takeIf { Character.isValidCodePoint(it) && it != 0 && it !in SURROGATES && !isControl(it) }
            ?.let { String(Character.toChars(it)) }

    /** Control characters other than line feed and tab, which never belong in a posting's text. */
    fun isControl(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) && codePoint != '\n'.code && codePoint != '\t'.code

    fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

    fun isAsciiLetterOrDigit(char: Char): Boolean = isAsciiLetter(char) || char in '0'..'9'
}
