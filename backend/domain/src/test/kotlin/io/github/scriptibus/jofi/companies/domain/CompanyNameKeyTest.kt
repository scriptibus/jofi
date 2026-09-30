// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class CompanyNameKeyTest {
    @Test
    fun `case, spacing, punctuation, full-width letters and trailing legal forms do not count`() {
        val key = CompanyNameKey.of("ACME Robotics GmbH")

        listOf(
            "acme robotics",
            "Acme-Robotics AG",
            "ACME  Robotics GmbH & Co. KG",
            "ＡＣＭＥ Robotics Inc.",
            "ACME Robotics UG (haftungsbeschränkt)",
        ).forEach { CompanyNameKey.of(it) shouldBe key }
    }

    @Test
    fun `other words make another company, and a name of legal forms only keeps them`() {
        CompanyNameKey.of("ACME Robotics Services GmbH") shouldNotBe CompanyNameKey.of("ACME Robotics GmbH")
        CompanyNameKey.of("ACME") shouldNotBe CompanyNameKey.of("ACME Robotics")
        CompanyNameKey.of("AG Co").value shouldBe "agco"
        CompanyNameKey.of("Müller AG").value shouldBe "müller"
    }

    @Test
    fun `the search text is the name's words without the legal form`() {
        CompanyNameKey.searchText(" ACME-Robotics GmbH & Co. KG") shouldBe "acme robotics"
        CompanyNameKey.searchText("...") shouldBe ""
    }
}
