// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/**
 * Redacts flagged values inside JSON text (tool call arguments, tool input schemas) without
 * breaking it: every string literal is decoded, redacted and encoded again, and a number that
 * contains a flagged value becomes the string `"[withheld]"`. Text that is not well-formed at the
 * string level is redacted as plain text (it was not valid JSON before either).
 */
internal object JsonStrings {
    private val NUMBER = Regex("-?\\d[\\d.eE+\\-]*")
    private const val HEX_DIGITS = 4
    private const val HEX_RADIX = 16
    private const val FIRST_PRINTABLE = 0x20
    private const val ESCAPES = "\"\\/bfnrt"
    private const val UNESCAPED = "\"\\/\b\u000C\n\r\t"

    fun redact(
        json: String,
        values: ValueRedactor,
    ): Redacted {
        val segments = segments(json) ?: return values.text(json)
        var count = 0
        val result = StringBuilder()
        for (segment in segments) {
            val redacted = if (segment.isString) string(segment, values) else outside(segment.raw, values)
            count += redacted.count
            result.append(redacted.text)
        }
        return Redacted(result.toString(), count)
    }

    private fun string(
        segment: Segment,
        values: ValueRedactor,
    ): Redacted {
        val redacted = values.text(segment.decoded)
        return if (redacted.count == 0) Redacted(segment.raw, 0) else Redacted(encode(redacted.text), redacted.count)
    }

    private fun outside(
        raw: String,
        values: ValueRedactor,
    ): Redacted {
        var count = 0
        val text =
            NUMBER.replace(raw) { number ->
                val redacted = values.text(number.value)
                count += redacted.count
                if (redacted.count == 0) number.value else encode(NeverSendFilter.REDACTION)
            }
        return Redacted(text, count)
    }

    private class Segment(
        val raw: String,
        val decoded: String,
        val isString: Boolean,
    )

    /** String literals and the text between them, or null for an unterminated or broken string. */
    private fun segments(json: String): List<Segment>? {
        val segments = mutableListOf<Segment>()
        var index = 0
        var broken = false
        while (index < json.length && !broken) {
            val quote = json.indexOf('"', index).let { if (it < 0) json.length else it }
            if (quote > index) segments += Segment(json.substring(index, quote), "", isString = false)
            val literal = if (quote < json.length) literalAt(json, quote) else null
            when {
                quote >= json.length -> {
                    index = json.length
                }

                literal == null -> {
                    broken = true
                }

                else -> {
                    segments += literal
                    index = quote + literal.raw.length
                }
            }
        }
        return segments.takeUnless { broken }
    }

    private fun literalAt(
        json: String,
        quote: Int,
    ): Segment? {
        val end = stringEnd(json, quote) ?: return null
        return decode(
            json.substring(quote + 1, end),
        )?.let { Segment(json.substring(quote, end + 1), it, isString = true) }
    }

    /** The index of the quote closing the string that opens at [start], or null. */
    private fun stringEnd(
        json: String,
        start: Int,
    ): Int? {
        var index = start + 1
        while (index < json.length) {
            when (json[index]) {
                '\\' -> index += 2
                '"' -> return index
                else -> index++
            }
        }
        return null
    }

    private fun decode(body: String): String? {
        val result = StringBuilder()
        var index = 0
        while (index < body.length) {
            if (body[index] != '\\') {
                result.append(body[index])
                index++
                continue
            }
            val (char, length) = escapeAt(body, index) ?: return null
            result.append(char)
            index += length
        }
        return result.toString()
    }

    /** The character an escape at [index] stands for and the escape's length, or null if broken. */
    private fun escapeAt(
        body: String,
        index: Int,
    ): Pair<Char, Int>? {
        val escape = body.getOrNull(index + 1)
        return if (escape == 'u') {
            body
                .substring(index + 2, minOf(index + 2 + HEX_DIGITS, body.length))
                .takeIf { it.length == HEX_DIGITS }
                ?.toIntOrNull(HEX_RADIX)
                ?.let { it.toChar() to 2 + HEX_DIGITS }
        } else {
            escape?.let { UNESCAPED.getOrNull(ESCAPES.indexOf(it)) }?.let { it to 2 }
        }
    }

    private fun encode(text: String): String =
        buildString {
            append('"')
            for (char in text) {
                when {
                    char == '"' || char == '\\' -> {
                        append('\\').append(char)
                    }

                    char.code < FIRST_PRINTABLE -> {
                        append(
                            "\\u",
                        ).append(char.code.toString(HEX_RADIX).padStart(HEX_DIGITS, '0'))
                    }

                    else -> {
                        append(char)
                    }
                }
            }
            append('"')
        }
}
