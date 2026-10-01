// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import java.nio.charset.Charset

/**
 * The character set to decode a fetched page with (#97): the `charset` of the `Content-Type` header, else a
 * `<meta charset>` or `<meta http-equiv>` within the first [SNIFF_BYTES] bytes, else UTF-8. An unknown name falls
 * back to UTF-8 too, so a hostile or odd page cannot fail the import. German career pages are still often
 * ISO-8859-1 or windows-1252.
 */
object PostingCharset {
    const val SNIFF_BYTES = 1_024
    private val HEADER_CHARSET = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]{1,40})", RegexOption.IGNORE_CASE)
    private val META_CHARSET =
        Regex("<meta[^>]{0,200}?charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]{1,40})", RegexOption.IGNORE_CASE)

    fun of(
        contentType: String?,
        body: ByteArray,
    ): Charset {
        val declared =
            contentType?.let { HEADER_CHARSET.find(it)?.groupValues?.get(1) }
                ?: META_CHARSET
                    .find(String(body, 0, minOf(body.size, SNIFF_BYTES), Charsets.ISO_8859_1))
                    ?.groupValues
                    ?.get(1)
        return declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }
}
