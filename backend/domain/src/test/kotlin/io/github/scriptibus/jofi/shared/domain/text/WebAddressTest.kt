// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.text

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WebAddressTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "acme.example",
            "/careers",
            "mailto:jobs@acme.example",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "https://user:secret@acme.example",
            "https:///no-host",
            "https://acme.example/with space",
            "https://acme.example:port",
            "https://acme.example/\u0000",
        ],
    )
    fun `web addresses are absolute http(s) URLs with a host and without credentials`(raw: String) {
        WebAddress.parse(raw).shouldBeNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://bücher.example/jobs",
            "https://xn--bcher-kva.example",
            "https://my_team.example/careers",
            "http://jobs.example:8080/list?q=kotlin&page=2#top",
            "https://jobs.example/@acme",
            "https://jobs.example/stellen/köln",
            "HTTP://ACME.example/Jobs",
        ],
    )
    fun `internationalised and underscore hosts, ports, paths and queries are kept as entered`(raw: String) {
        WebAddress.parse(raw)?.value shouldBe raw
        WebAddress.parse(raw).toString() shouldBe raw
    }

    @Test
    fun `a web address may be exactly as long as the limit`() {
        val prefix = "https://acme.example/"
        val longest = prefix + "x".repeat(WebAddress.MAX_LENGTH - prefix.length)

        WebAddress.parse(longest)?.value shouldBe longest
        WebAddress.parse(longest + "x").shouldBeNull()
        shouldThrow<IllegalArgumentException> { WebAddress("ftp://acme.example") }
    }
}
