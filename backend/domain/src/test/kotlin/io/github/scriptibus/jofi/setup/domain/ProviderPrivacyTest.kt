// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.LocalDate

class ProviderPrivacyTest {
    private val day = LocalDate.parse("2026-09-30")
    private val text = LocalizedText("summary", "Zusammenfassung")
    private val evidence = PrivacyEvidence(URI("https://example.org/terms"), "a quote")
    private val claim = PrivacyClaim(PrivacyClaimStatus.UNKNOWN, text, listOf(evidence))
    private val disclaimer = PrivacyDisclaimer("setup_provider_privacy_disclaimer", text)

    private fun entry(
        kind: ProviderKind,
        checkedOn: LocalDate = day,
    ) = ProviderPrivacyInfo(kind, checkedOn, claim, claim, claim)

    private fun catalog(entries: List<ProviderPrivacyInfo>) = ProviderPrivacyCatalog(day, 6, disclaimer, entries)

    @Test
    fun `a claim needs a source and the source must be https`() {
        shouldThrow<IllegalArgumentException> { PrivacyClaim(PrivacyClaimStatus.YES, text, emptyList()) }
        shouldThrow<IllegalArgumentException> { PrivacyEvidence(URI("http://example.org"), "quote") }
        shouldThrow<IllegalArgumentException> { PrivacyEvidence(URI("https://example.org"), " ") }
        shouldThrow<IllegalArgumentException> { LocalizedText("only English", "") }
        shouldThrow<IllegalArgumentException> { PrivacyDisclaimer("", text) }
    }

    @Test
    fun `the catalog has exactly one entry per provider kind, none newer than itself`() {
        catalog(ProviderKind.entries.reversed().map { entry(it) }).entries.map { it.provider } shouldBe
            ProviderKind.entries
        shouldThrow<IllegalArgumentException> { catalog(ProviderKind.entries.drop(1).map { entry(it) }) }
        shouldThrow<IllegalArgumentException> {
            catalog(ProviderKind.entries.map { entry(it) } + entry(ProviderKind.OPENAI))
        }
        shouldThrow<IllegalArgumentException> {
            catalog(ProviderKind.entries.map { entry(it, if (it == ProviderKind.GEMINI) day.plusDays(1) else day) })
        }
        shouldThrow<IllegalArgumentException> {
            ProviderPrivacyCatalog(day, 0, disclaimer, ProviderKind.entries.map { entry(it) })
        }
    }

    @Test
    fun `an entry turns stale the day after its limit has passed`() {
        val old = LocalDate.parse("2026-03-30")
        val overview =
            catalog(ProviderKind.entries.map { entry(it, if (it == ProviderKind.MISTRAL) old else day) })
                .overviewOn(LocalDate.parse("2026-09-30"))
        overview.entries.single { it.info.provider == ProviderKind.MISTRAL }.stale shouldBe false
        overview.entries.single { it.info.provider == ProviderKind.OPENAI }.stale shouldBe false

        val later = catalog(ProviderKind.entries.map { entry(it, old) }).overviewOn(LocalDate.parse("2026-10-01"))
        later.entries.all { it.stale } shouldBe true
        later.staleAfterMonths shouldBe 6
        later.disclaimer shouldBe disclaimer
    }
}
