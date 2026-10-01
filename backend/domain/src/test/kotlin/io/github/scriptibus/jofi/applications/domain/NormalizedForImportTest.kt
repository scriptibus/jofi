// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class NormalizedForImportTest {
    @Test
    fun `tracking parameters are stripped, the rest of the query is kept and sorted`() {
        val address = WebAddress("https://Jobs.example/42?b=2&utm_source=mail&a=1&gclid=x&fbclid=y")

        address.normalizedForImport() shouldBe WebAddress("https://jobs.example/42?a=1&b=2")
    }

    @Test
    fun `the scheme and host are lower-cased, the default port and fragment are dropped`() {
        WebAddress("HTTPS://Jobs.Example:443/42#section").normalizedForImport() shouldBe
            WebAddress("https://jobs.example/42")
        WebAddress("http://jobs.example:80/42").normalizedForImport() shouldBe WebAddress("http://jobs.example/42")
    }

    @Test
    fun `a non-default port and a query made only of tracking parameters are handled`() {
        WebAddress("https://jobs.example:8443/42").normalizedForImport() shouldBe
            WebAddress("https://jobs.example:8443/42")
        WebAddress("https://jobs.example/42?utm_source=mail").normalizedForImport() shouldBe
            WebAddress("https://jobs.example/42")
    }

    @Test
    fun `the same link shared with different tracking parameters normalises to the same address`() {
        val first = WebAddress("https://jobs.example/42?utm_source=newsletter&utm_campaign=q4")
        val second = WebAddress("https://jobs.example/42?utm_source=social")

        first.normalizedForImport() shouldBe second.normalizedForImport()
    }

    @Test
    fun `a host java net URI cannot parse as a server name has no normalised form`() {
        WebAddress("https://my_team.example/job").normalizedForImport() shouldBe null
    }

    @Test
    fun `hosts java net URI rejects have no normalised form, and nothing is thrown`() {
        listOf(
            "https://[abc/x",
            "https://exa%mple.com/x",
            "https://exa|mple.com/x",
            "https://exa\"mple.com/",
            "https://b\u00fccher.example/" + "a".repeat(2_030),
        ).forEach { raw ->
            WebAddress.parse(raw)?.normalizedForImport() shouldBe null
        }
    }

    @Test
    fun `a route fragment of a single-page site is kept, a plain fragment dropped, ref is no tracking parameter`() {
        WebAddress("https://jobs.example/careers#/job/1").normalizedForImport() shouldBe
            WebAddress("https://jobs.example/careers#/job/1")
        WebAddress("https://jobs.example/careers#/job/1").normalizedForImport() shouldNotBe
            WebAddress("https://jobs.example/careers#/job/2").normalizedForImport()
        WebAddress("https://jobs.example/job?ref=1234&utm_source=x").normalizedForImport() shouldBe
            WebAddress("https://jobs.example/job?ref=1234")
    }

    @Test
    fun `a trailing dot on the host names the same site`() {
        WebAddress("https://jobs.example./42").normalizedForImport() shouldBe WebAddress("https://jobs.example/42")
    }
}
