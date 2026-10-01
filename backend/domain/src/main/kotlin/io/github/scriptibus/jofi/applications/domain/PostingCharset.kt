// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import java.nio.charset.Charset

/**
 * The character set to decode a fetched page with (#97): the `charset` of the `Content-Type` header, else a
 * `<meta charset>` or `<meta http-equiv>` within the first [SNIFF_BYTES] bytes, else UTF-8. An unknown name falls
 * back to UTF-8 too, so a hostile or odd page cannot fail the import. German career pages are still often
 * ISO-8859-1 or windows-1252. As browsers do (WHATWG encoding standard) a declared ISO-8859-1 or US-ASCII means
 * windows-1252, so `\u20AC \u2013 \u201E \u201C` survive; a byte order mark decides before any declaration.
 */
object PostingCharset {
    const val SNIFF_BYTES = 1_024
    private val HEADER_CHARSET = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]{1,40})", RegexOption.IGNORE_CASE)
    private val META_CHARSET =
        Regex("<meta[^>]{0,200}?charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]{1,40})", RegexOption.IGNORE_CASE)

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    private val UTF8_BOM = "\uFEFF".toByteArray(Charsets.UTF_8)
    private val UTF16_BE_BOM = "\uFEFF".toByteArray(Charsets.UTF_16BE)
    private val UTF16_LE_BOM = "\uFEFF".toByteArray(Charsets.UTF_16LE)

    fun of(
        contentType: String?,
        body: ByteArray,
    ): Charset = byteOrderMark(body) ?: declared(contentType, body)?.let(::asBrowsersRead) ?: Charsets.UTF_8

    private fun byteOrderMark(body: ByteArray): Charset? =
        when {
            body.startsWith(UTF8_BOM) -> Charsets.UTF_8
            body.startsWith(UTF16_BE_BOM) -> Charsets.UTF_16BE
            body.startsWith(UTF16_LE_BOM) -> Charsets.UTF_16LE
            else -> null
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun declared(
        contentType: String?,
        body: ByteArray,
    ): Charset? {
        val name =
            contentType?.let { HEADER_CHARSET.find(it)?.groupValues?.get(1) }
                ?: META_CHARSET
                    .find(String(body, 0, minOf(body.size, SNIFF_BYTES), Charsets.ISO_8859_1))
                    ?.groupValues
                    ?.get(1)
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() }
    }

    private fun asBrowsersRead(charset: Charset): Charset =
        if (charset == Charsets.ISO_8859_1 || charset == Charsets.US_ASCII) WINDOWS_1252 else charset
}
