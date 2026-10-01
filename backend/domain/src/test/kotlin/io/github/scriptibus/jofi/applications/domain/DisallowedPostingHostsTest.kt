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
}
