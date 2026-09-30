// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.domain.LocalizedText
import io.github.scriptibus.jofi.setup.domain.PrivacyClaim
import io.github.scriptibus.jofi.setup.domain.PrivacyClaimStatus
import io.github.scriptibus.jofi.setup.domain.PrivacyDisclaimer
import io.github.scriptibus.jofi.setup.domain.PrivacyEvidence
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyCatalog
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyInfo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ListProviderPrivacyInfoUseCaseTest {
    private val text = LocalizedText("summary", "Zusammenfassung")
    private val claim =
        PrivacyClaim(PrivacyClaimStatus.YES, text, listOf(PrivacyEvidence(URI("https://example.org"), "quote")))
    private val checkedOn = LocalDate.parse("2026-09-30")
    private val catalog =
        ProviderPrivacyCatalog(
            checkedOn,
            6,
            PrivacyDisclaimer("setup_provider_privacy_disclaimer", text),
            ProviderKind.entries.map { ProviderPrivacyInfo(it, checkedOn, claim, claim, claim) },
        )

    private fun at(instant: String) =
        ListProviderPrivacyInfoUseCase(catalog, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))

    @Test
    fun `lists every provider kind with the disclaimer`() {
        val overview = at("2026-10-01T00:00:00Z").execute()

        overview.entries.map { it.info.provider } shouldBe ProviderKind.entries
        overview.disclaimer.key shouldBe "setup_provider_privacy_disclaimer"
        overview.checkedOn shouldBe checkedOn
        overview.entries.any { it.stale } shouldBe false
    }

    @Test
    fun `flags entries once they are older than the catalog allows, by today's date`() {
        at("2027-03-30T23:59:59Z").execute().entries.any { it.stale } shouldBe false
        at("2027-03-31T00:00:00Z").execute().entries.all { it.stale } shouldBe true
    }
}
