// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PostingCharsetTest {
    private fun of(
        contentType: String?,
        body: String = "",
    ) = PostingCharset.of(contentType, body.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `the Content-Type header wins over the page`() {
        of("text/html; charset=ISO-8859-1", "<meta charset=utf-8>") shouldBe Charsets.ISO_8859_1
        of("text/html;charset=\"windows-1252\"") shouldBe charset("windows-1252")
    }

    @Test
    fun `a meta tag within the first kilobyte is used when the header names none`() {
        of("text/html", "<html><head><meta charset=\"iso-8859-15\">") shouldBe charset("ISO-8859-15")
        of(null, "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1252\">") shouldBe
            charset("windows-1252")
    }

    @Test
    fun `no declaration or an unknown name means UTF-8`() {
        of("text/html") shouldBe Charsets.UTF_8
        of("text/html; charset=nonsense-9") shouldBe Charsets.UTF_8
        of("text/html; charset=a/b") shouldBe Charsets.UTF_8
        of(null, "x".repeat(2_000) + "<meta charset=latin1>") shouldBe Charsets.UTF_8
    }
}
