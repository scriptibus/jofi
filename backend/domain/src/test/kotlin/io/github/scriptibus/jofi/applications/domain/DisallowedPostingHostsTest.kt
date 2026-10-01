// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DisallowedPostingHostsTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://www.linkedin.com/jobs/view/1",
            "https://de.indeed.com/stellen",
            "https://www.stepstone.de/stellenangebote--x",
            "https://stepstone.at/x",
        ],
    )
    fun `sites the spec never scrapes are disallowed, whatever their country domain`(url: String) {
        DisallowedPostingHosts.isDisallowed(WebAddress(url)) shouldBe true
    }

    @Test
    fun `a company site that merely mentions one of those names in its own domain is not disallowed`() {
        DisallowedPostingHosts.isDisallowed(WebAddress("https://linkedin-consulting.example/jobs/1")) shouldBe false
        DisallowedPostingHosts.isDisallowed(WebAddress("https://jobs.example/careers")) shouldBe false
    }

    @Test
    fun `the shorteners the sites run themselves are disallowed, hosts as a fetch reports them too`() {
        DisallowedPostingHosts.isDisallowed(WebAddress("https://lnkd.in/abc")) shouldBe true
        DisallowedPostingHosts.isDisallowedHost("lnkd.in") shouldBe true
        DisallowedPostingHosts.isDisallowedHost("WWW.LinkedIn.com.") shouldBe true
        DisallowedPostingHosts.isDisallowedHost("jobs.example") shouldBe false
        DisallowedPostingHosts.isDisallowedHost("notlnkd.in") shouldBe false
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://indeedjobs.com/careers",
            "https://www.indeedjobs.com/x",
            "https://indeed.co.uk/jobs",
            "https://uk.linkedin.com/jobs",
            "https://WWW.LINKEDIN.COM./x",
            "https://www.\uFF4Cinkedin.com/x",
        ],
    )
    fun `other domains of the sites and country suffixes are disallowed`(url: String) {
        DisallowedPostingHosts.isDisallowed(WebAddress(url)) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://indeed.example.org/x", "https://careers.indeed-partner.example/x"])
    fun `a brand name that is not the registrable name of the host is no match`(url: String) {
        DisallowedPostingHosts.isDisallowed(WebAddress(url)) shouldBe false
    }
}
