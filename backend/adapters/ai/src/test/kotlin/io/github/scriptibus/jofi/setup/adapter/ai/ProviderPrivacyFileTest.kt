// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.domain.PrivacyClaimStatus
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ProviderPrivacyFileTest {
    private val catalog = ProviderPrivacyFile.load()

    @Test
    fun `every provider kind has exactly one entry`() {
        catalog.entries.map { it.provider } shouldContainExactly ProviderKind.entries
    }

    @Test
    fun `every claim is dated and quotes an https page`() {
        (catalog.checkedOn <= LocalDate.parse("2026-09-30")) shouldBe true
        catalog.entries.forEach { entry ->
            (entry.checkedOn <= catalog.checkedOn) shouldBe true
            listOf(entry.zeroDataRetention, entry.noTraining, entry.dataLocation).forEach { claim ->
                claim.evidence.shouldNotBeEmpty()
                claim.evidence.forEach { it.source.scheme shouldBe "https" }
                claim.summary.en.shouldNotBeBlank()
                claim.summary.de.shouldNotBeBlank()
            }
        }
    }

    @Test
    fun `the vendors cite only their own official pages`() {
        catalog.entries.filter { it.provider != ProviderKind.OPENAI_COMPATIBLE }.forEach { entry ->
            val hosts =
                listOf(entry.zeroDataRetention, entry.noTraining, entry.dataLocation)
                    .flatMap { claim -> claim.evidence.map { it.source.host } }
            hosts.forEach { host -> (host in OFFICIAL_HOSTS.getValue(entry.provider)) shouldBe true }
        }
    }

    @Test
    fun `an OpenAI-compatible endpoint depends on the endpoint`() {
        val compatible = catalog.entries.single { it.provider == ProviderKind.OPENAI_COMPATIBLE }
        listOf(compatible.zeroDataRetention, compatible.noTraining, compatible.dataLocation)
            .map { it.status }
            .toSet() shouldBe setOf(PrivacyClaimStatus.DEPENDS_ON_ENDPOINT)
    }

    @Test
    fun `the disclaimer and the stale limit are set`() {
        catalog.disclaimer.key shouldBe "setup_provider_privacy_disclaimer"
        catalog.disclaimer.text.en
            .shouldNotBeBlank()
        catalog.disclaimer.text.de
            .shouldNotBeBlank()
        catalog.staleAfterMonths shouldBe 6
    }

    @Test
    fun `a broken file stops startup instead of showing unsourced claims`() {
        val valid = requireNotNull(javaClass.getResource("provider-privacy.json")).readText()
        shouldThrowAny { ProviderPrivacyFile.parse(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) }
        shouldThrowAny {
            ProviderPrivacyFile.parse(
                valid.replace("\"https://docs.ollama.com/faq\"", "\"http://docs.ollama.com/faq\""),
            )
        }
        shouldThrowAny {
            ProviderPrivacyFile.parse(
                valid.replaceFirst("\"status\": \"ON_REQUEST\"", "\"status\": \"MAYBE\""),
            )
        }
        shouldThrowAny {
            ProviderPrivacyFile.parse(
                valid.replace("\"provider\": \"MISTRAL\"", "\"provider\": \"GEMINI\""),
            )
        }
        shouldThrowAny {
            ProviderPrivacyFile.parse(
                valid.replaceFirst(
                    "\"checkedOn\": \"2026-09-30\",\n      \"zero",
                    "\"checkedOn\": \"2026-10-01\",\n      \"zero",
                ),
            )
        }
        shouldThrowAny { ProviderPrivacyFile.parse("""{"schemaVersion":1,"checkedOn":"2026-09-30"}""") }
    }

    private companion object {
        val OFFICIAL_HOSTS =
            mapOf(
                ProviderKind.ANTHROPIC to setOf("platform.claude.com", "privacy.claude.com", "www.anthropic.com"),
                ProviderKind.OPENAI to setOf("developers.openai.com", "openai.com"),
                ProviderKind.GEMINI to setOf("ai.google.dev"),
                ProviderKind.MISTRAL to setOf("docs.mistral.ai", "help.mistral.ai", "legal.mistral.ai"),
            )
    }
}
