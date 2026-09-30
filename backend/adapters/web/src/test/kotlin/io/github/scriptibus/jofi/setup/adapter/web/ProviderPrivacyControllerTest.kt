// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.ListProviderPrivacyInfoUseCase
import io.github.scriptibus.jofi.setup.domain.LocalizedText
import io.github.scriptibus.jofi.setup.domain.PrivacyClaim
import io.github.scriptibus.jofi.setup.domain.PrivacyClaimStatus
import io.github.scriptibus.jofi.setup.domain.PrivacyDisclaimer
import io.github.scriptibus.jofi.setup.domain.PrivacyEvidence
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyCatalog
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyInfo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The privacy info endpoint over the real use case and a small catalog, on a fixed day. */
@WebMvcTest(ProviderPrivacyController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ProviderPrivacyControllerTest.UseCases::class)
class ProviderPrivacyControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    @TestConfiguration
    class UseCases {
        @Bean
        fun listProviderPrivacyInfo(): ListProviderPrivacyInfoUseCase {
            val text = LocalizedText("Available on request.", "Auf Anfrage.")
            val claim =
                PrivacyClaim(
                    PrivacyClaimStatus.ON_REQUEST,
                    text,
                    listOf(PrivacyEvidence(URI("https://example.org/terms"), "We may offer it.")),
                )
            val fresh = LocalDate.parse("2026-09-30")
            val entries =
                ProviderKind.entries.map {
                    val checkedOn = if (it == ProviderKind.MISTRAL) LocalDate.parse("2026-01-15") else fresh
                    ProviderPrivacyInfo(it, checkedOn, claim, claim, claim)
                }
            val disclaimer =
                PrivacyDisclaimer("setup_provider_privacy_disclaimer", LocalizedText("Verify.", "Prüfe."))
            val catalog = ProviderPrivacyCatalog(fresh, 6, disclaimer, entries)
            return ListProviderPrivacyInfoUseCase(
                catalog,
                Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC),
            )
        }
    }

    @Test
    fun `lists every provider kind with sources, dates, stale flags and the disclaimer`() {
        mvc
            .get()
            .uri("/api/setup/providers/privacy")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"checkedOn":"2026-09-30","staleAfterMonths":6,
                 "disclaimer":{"key":"setup_provider_privacy_disclaimer","text":{"en":"Verify.","de":"Prüfe."}},
                 "providers":[
                  {"kind":"ANTHROPIC","checkedOn":"2026-09-30","stale":false,
                   "zeroDataRetention":{"status":"ON_REQUEST",
                     "summary":{"en":"Available on request.","de":"Auf Anfrage."},
                     "evidence":[{"source":"https://example.org/terms","quote":"We may offer it."}]}},
                  {"kind":"OPENAI","stale":false},
                  {"kind":"GEMINI","stale":false},
                  {"kind":"MISTRAL","checkedOn":"2026-01-15","stale":true},
                  {"kind":"OPENAI_COMPATIBLE","stale":false,"noTraining":{"status":"ON_REQUEST"},
                   "dataLocation":{"status":"ON_REQUEST"}}
                 ]}
                """.trimIndent(),
            )
    }
}
