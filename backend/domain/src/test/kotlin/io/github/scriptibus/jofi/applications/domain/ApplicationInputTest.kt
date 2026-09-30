// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class ApplicationInputTest {
    private val company = CompanyRef(UUID.fromString("00000000-0000-0000-0000-00000000000c"))

    private fun ApplicationInput.valid(): ApplicationDetails =
        validate().shouldBeInstanceOf<ApplicationValidation.Valid<ApplicationDetails>>().value

    private fun ApplicationInput.violations(): List<ApplicationViolation> =
        validate().shouldBeInstanceOf<ApplicationValidation.Invalid>().violations

    @Test
    fun `text is normalized to NFC and trimmed, blank optional text is absent`() {
        val details =
            ApplicationInput(
                title = "  Entwickler für Bücher ",
                company = company,
                location = " ",
                portalNotes = "\n",
                declineReason = DeclineReasonInput(DeclineCategory.TIMING, "  "),
            ).valid()

        details.title shouldBe "Entwickler für Bücher"
        details.location shouldBe null
        details.portalNotes shouldBe null
        details.declineReason shouldBe DeclineReason(DeclineCategory.TIMING)
        details.languageAndTone shouldBe LanguageAndTone.UNKNOWN
    }

    private val complete =
        ApplicationInput(
            "Backend Engineer",
            company,
            "Berlin",
            60,
            EmploymentType.FULL_TIME,
            Seniority.SENIOR,
            LocalDate.parse("2026-10-31"),
            HowApplied.PORTAL,
            "Account",
            PayBandInput(
                BigDecimal("70000"),
                BigDecimal("85000.5"),
                " eur ",
                PayPeriod.YEAR,
                PaySourceKind.POSTING,
                "ignored",
            ),
            LanguageAndToneInput(" de ", "en-GB", FormOfAddress.DU, Tone.PERSONAL),
            DeclineReasonInput(DeclineCategory.SALARY, "Too low"),
            OfferInput(PayInput(BigDecimal("80000"), "EUR", PayPeriod.YEAR), " 10 % ", vacationDays = 30),
        )

    @Test
    fun `every field is kept as a domain value`() {
        val details = complete.valid()

        details.remoteShare shouldBe RemoteShare(60)
        details.payBand shouldBe
            PayBand(
                BigDecimal("70000.00"),
                BigDecimal("85000.50"),
                CurrencyCode("EUR"),
                PayPeriod.YEAR,
                PaySource.Posting,
            )
        details.languageAndTone shouldBe
            LanguageAndTone(LanguageTag("de"), LanguageTag("en-GB"), FormOfAddress.DU, Tone.PERSONAL)
        details.offer shouldBe
            OfferDetails(
                Pay(BigDecimal("80000.00"), CurrencyCode("EUR"), PayPeriod.YEAR),
                bonus = "10 %",
                vacationDays = 30,
            )
    }

    @Test
    fun `an estimated pay band needs its basis and confidence`() {
        val estimate = PayBandInput(BigDecimal.ONE, null, "EUR", PayPeriod.HOUR, PaySourceKind.ESTIMATED)

        ApplicationInput("X", company, payBand = estimate).violations() shouldContainExactlyInAnyOrder
            listOf(
                ApplicationViolation(ApplicationField.PAY_ESTIMATE_BASIS, ApplicationProblem.REQUIRED),
                ApplicationViolation(ApplicationField.PAY_ESTIMATE_CONFIDENCE, ApplicationProblem.REQUIRED),
            )
        val complete = estimate.copy(estimateBasis = " Levels ", estimateConfidence = EstimateConfidence.MEDIUM)
        ApplicationInput("X", company, payBand = complete).valid().payBand?.source shouldBe
            PaySource.Estimated("Levels", EstimateConfidence.MEDIUM)
    }

    @Test
    fun `broken pay bands name each problem`() {
        fun band(
            min: String?,
            max: String?,
            currency: String = "EUR",
        ) = ApplicationInput(
            "X",
            company,
            payBand =
                PayBandInput(
                    min?.let(::BigDecimal),
                    max?.let(::BigDecimal),
                    currency,
                    PayPeriod.YEAR,
                    PaySourceKind.RECRUITER,
                ),
        )

        band(null, null).violations() shouldBe
            listOf(ApplicationViolation(ApplicationField.PAY_MIN, ApplicationProblem.REQUIRED))
        band("2", "1").violations() shouldBe
            listOf(ApplicationViolation(ApplicationField.PAY_MAX, ApplicationProblem.MIN_ABOVE_MAX))
        band("-1", "1.001", "Euro").violations() shouldContainExactlyInAnyOrder
            listOf(
                ApplicationViolation(ApplicationField.PAY_MIN, ApplicationProblem.OUT_OF_RANGE),
                ApplicationViolation(ApplicationField.PAY_MAX, ApplicationProblem.TOO_PRECISE),
                ApplicationViolation(ApplicationField.PAY_CURRENCY, ApplicationProblem.INVALID_CURRENCY),
            )
        band("1", null, " ").violations() shouldBe
            listOf(ApplicationViolation(ApplicationField.PAY_CURRENCY, ApplicationProblem.REQUIRED))
    }

    private val broken =
        ApplicationInput(
            title = " ",
            company = company,
            location = "x".repeat(ApplicationDetails.MAX_LOCATION_LENGTH + 1),
            remoteShare = 101,
            portalNotes = "a\u0000b",
            languageAndTone = LanguageAndToneInput("deutsch", "en_GB"),
            declineReason =
                DeclineReasonInput(
                    DeclineCategory.OTHER,
                    "x".repeat(DeclineReason.MAX_TEXT_LENGTH + 1),
                ),
            offer =
                OfferInput(
                    PayInput(BigDecimal("0.001"), "€", PayPeriod.MONTH),
                    bonus = "x".repeat(OfferDetails.MAX_TEXT_LENGTH + 1),
                    benefits = "\u0000",
                    remoteShare = -1,
                    vacationDays = OfferDetails.MAX_VACATION_DAYS + 1,
                    noticePeriod = "x".repeat(OfferDetails.MAX_NOTICE_PERIOD_LENGTH + 1),
                ),
        )

    @Test
    fun `every other broken field is reported at once`() {
        broken.violations() shouldContainExactlyInAnyOrder
            listOf(
                ApplicationViolation(ApplicationField.TITLE, ApplicationProblem.REQUIRED),
                ApplicationViolation(ApplicationField.LOCATION, ApplicationProblem.TOO_LONG),
                ApplicationViolation(ApplicationField.REMOTE_SHARE, ApplicationProblem.OUT_OF_RANGE),
                ApplicationViolation(ApplicationField.PORTAL_NOTES, ApplicationProblem.INVALID_CHARACTER),
                ApplicationViolation(ApplicationField.POSTING_LANGUAGE, ApplicationProblem.INVALID_LANGUAGE),
                ApplicationViolation(ApplicationField.APPLICATION_LANGUAGE, ApplicationProblem.INVALID_LANGUAGE),
                ApplicationViolation(ApplicationField.DECLINE_REASON_TEXT, ApplicationProblem.TOO_LONG),
                ApplicationViolation(ApplicationField.OFFER_SALARY, ApplicationProblem.TOO_PRECISE),
                ApplicationViolation(ApplicationField.OFFER_SALARY_CURRENCY, ApplicationProblem.INVALID_CURRENCY),
                ApplicationViolation(ApplicationField.OFFER_BONUS, ApplicationProblem.TOO_LONG),
                ApplicationViolation(ApplicationField.OFFER_BENEFITS, ApplicationProblem.INVALID_CHARACTER),
                ApplicationViolation(ApplicationField.OFFER_REMOTE_SHARE, ApplicationProblem.OUT_OF_RANGE),
                ApplicationViolation(ApplicationField.OFFER_VACATION_DAYS, ApplicationProblem.OUT_OF_RANGE),
                ApplicationViolation(ApplicationField.OFFER_NOTICE_PERIOD, ApplicationProblem.TOO_LONG),
            )
    }

    @Test
    fun `values at exactly each limit are valid`() {
        val details =
            ApplicationInput(
                "t".repeat(ApplicationDetails.MAX_TITLE_LENGTH),
                company,
                "l".repeat(ApplicationDetails.MAX_LOCATION_LENGTH),
                RemoteShare.MAX_PERCENT,
                portalNotes = "n".repeat(ApplicationDetails.MAX_NOTES_LENGTH),
                payBand = PayBandInput(Amount.MAX, Amount.MAX, "EUR", PayPeriod.YEAR, PaySourceKind.POSTING),
                offer = OfferInput(vacationDays = 0, remoteShare = 0),
            ).valid()

        details.title.length shouldBe ApplicationDetails.MAX_TITLE_LENGTH
        details.offer shouldBe OfferDetails(remoteShare = RemoteShare(0), vacationDays = 0)
    }

    @Test
    fun `an offer without any detail is no offer`() {
        ApplicationInput("X", company, offer = OfferInput(bonus = " ")).valid().offer shouldBe null
    }

    @Test
    fun `input prints none of the user's notes`() {
        val input =
            ApplicationInput(
                "Secret title",
                company,
                portalNotes = "Secret",
                declineReason = DeclineReasonInput(DeclineCategory.OTHER, "Secret"),
                offer = OfferInput(bonus = "Secret"),
            )
        listOf(input, input.declineReason, input.offer).forEach { it.toString() shouldNotContain "Secret" }
    }
}
