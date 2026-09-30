// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.LocalDate

class ApplicationValuesTest {
    private val eur = CurrencyCode("EUR")

    @Test
    fun `amounts are 0 to the maximum with exactly two decimals`() {
        Amount.isValid(BigDecimal("0.00")) shouldBe true
        Amount.isValid(Amount.MAX) shouldBe true
        Amount.isValid(BigDecimal("1")) shouldBe false
        Amount.isValid(BigDecimal("-1.00")) shouldBe false
        Amount.problemOf(BigDecimal("1.230")) shouldBe null
        Amount.normalized(BigDecimal("1.230")) shouldBe BigDecimal("1.23")
        Amount.normalized(BigDecimal("1E+3")) shouldBe BigDecimal("1000.00")
        Amount.problemOf(BigDecimal("1.234")) shouldBe ApplicationProblem.TOO_PRECISE
        Amount.problemOf(BigDecimal("-0.01")) shouldBe ApplicationProblem.OUT_OF_RANGE
        Amount.problemOf(Amount.MAX + BigDecimal("0.01")) shouldBe ApplicationProblem.OUT_OF_RANGE
    }

    @Test
    fun `a pay band names at least one amount and its minimum is not above its maximum`() {
        val amount = BigDecimal("50000.00")
        PayBand(amount, amount, eur, PayPeriod.YEAR, PaySource.Posting).min shouldBe amount
        PayBand(null, amount, eur, PayPeriod.YEAR, PaySource.Recruiter).max shouldBe amount
        shouldThrow<IllegalArgumentException> { PayBand(null, null, eur, PayPeriod.YEAR, PaySource.Posting) }
        shouldThrow<IllegalArgumentException> {
            PayBand(amount, BigDecimal("49999.99"), eur, PayPeriod.YEAR, PaySource.Posting)
        }
        shouldThrow<IllegalArgumentException> { PayBand(BigDecimal("1"), null, eur, PayPeriod.YEAR, PaySource.Posting) }
        shouldThrow<IllegalArgumentException> { Pay(BigDecimal("-1.00"), eur, PayPeriod.YEAR) }
    }

    @Test
    fun `an estimate names its basis`() {
        PaySource.Estimated("Levels in Berlin", EstimateConfidence.LOW).basis shouldBe "Levels in Berlin"
        shouldThrow<IllegalArgumentException> { PaySource.Estimated(" ", EstimateConfidence.LOW) }
        shouldThrow<IllegalArgumentException> {
            PaySource.Estimated("x".repeat(PaySource.Estimated.MAX_BASIS_LENGTH + 1), EstimateConfidence.LOW)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["eur", "EU", "EURO", "E1R", "ÄUR", ""])
    fun `a currency code is three letters A to Z`(code: String) {
        CurrencyCode.isValid(code) shouldBe false
        shouldThrow<IllegalArgumentException> { CurrencyCode(code) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["de", "en", "DE", "gsw", "de-CH", "en-GB", "zh-Hant-TW", "sr-Latn-RS", "de-1996"])
    fun `BCP 47 language tags are accepted as entered`(tag: String) {
        LanguageTag(tag).value shouldBe tag
    }

    @ParameterizedTest
    @ValueSource(strings = ["d", "deutsch", "de_DE", "de-", "de-toolongsubtag", "dé", " de", ""])
    fun `anything else is not a language tag`(tag: String) {
        LanguageTag.isValid(tag) shouldBe false
    }

    @ParameterizedTest
    @CsvSource(
        "de, de",
        "DE, de",
        "de-ch, de-CH",
        "EN-gb, en-GB",
        "zh-hant-tw, zh-Hant-TW",
        "SR-LATN, sr-Latn",
        "es-419, es-419",
        "de-1996, de-1996",
        "gsw-CH, gsw-CH",
        "de-CH-1996, de-CH-1996",
        "en-US-x-TWAIN, en-US-x-twain",
        "de-a-BB-cc, de-a-bb-cc",
        "en-GB-OXENDICT, en-GB-oxendict",
    )
    fun `language tags are brought into canonical case`(
        tag: String,
        canonical: String,
    ) {
        LanguageTag.canonical(tag) shouldBe canonical
    }

    @Test
    fun `canonical case does not depend on the default locale`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            LanguageTag.canonical("TR-latn-IT") shouldBe "tr-Latn-IT"
            LanguageTag.canonical("IT") shouldBe "it"
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    @Test
    fun `pay prints neither amounts nor the estimate basis`() {
        val amount = BigDecimal("98765.43")
        val estimate = PaySource.Estimated("Secret basis", EstimateConfidence.HIGH)
        listOf(
            PayBand(amount, amount, eur, PayPeriod.YEAR, estimate),
            Pay(amount, eur, PayPeriod.MONTH),
            estimate,
        ).forEach {
            it.toString() shouldNotContain "98765"
            it.toString() shouldNotContain "Secret"
        }
    }

    @Test
    fun `a language tag has at most its maximum length`() {
        val longest = "de" + "-abcdefgh".repeat(3) + "-abcde"
        longest.length shouldBe LanguageTag.MAX_LENGTH
        LanguageTag(longest).value shouldBe longest
        LanguageTag.isValid("$longest-a") shouldBe false
    }

    @Test
    fun `the application language follows the posting's until the user chooses one`() {
        LanguageAndTone(postingLanguage = LanguageTag("de")).effectiveApplicationLanguage shouldBe LanguageTag("de")
        LanguageAndTone(LanguageTag("de"), LanguageTag("en")).effectiveApplicationLanguage shouldBe LanguageTag("en")
        LanguageAndTone.UNKNOWN.effectiveApplicationLanguage shouldBe null
    }

    @Test
    fun `a remote share is 0 to 100 percent`() {
        RemoteShare(0).percent shouldBe 0
        RemoteShare(RemoteShare.MAX_PERCENT).percent shouldBe 100
        shouldThrow<IllegalArgumentException> { RemoteShare(-1) }
        shouldThrow<IllegalArgumentException> { RemoteShare(RemoteShare.MAX_PERCENT + 1) }
    }

    @Test
    fun `an offer names at least one detail and prints none of its text`() {
        val offer = OfferDetails(bonus = "Secret bonus", vacationDays = OfferDetails.MAX_VACATION_DAYS)

        offer.toString() shouldNotContain "Secret"
        OfferDetails(startDate = LocalDate.parse("2027-01-01")).startDate shouldBe LocalDate.parse("2027-01-01")
        shouldThrow<IllegalArgumentException> { OfferDetails() }
        shouldThrow<IllegalArgumentException> { OfferDetails(vacationDays = -1) }
        shouldThrow<IllegalArgumentException> {
            OfferDetails(
                noticePeriod =
                    "x".repeat(OfferDetails.MAX_NOTICE_PERIOD_LENGTH + 1),
            )
        }
        shouldThrow<IllegalArgumentException> { OfferDetails(benefits = " ") }
    }

    @Test
    fun `a decline reason has a category and optional text it does not print`() {
        DeclineReason(DeclineCategory.NO_REASON_GIVEN).text shouldBe null
        DeclineReason(DeclineCategory.SALARY, "Secret").toString() shouldNotContain "Secret"
        shouldThrow<IllegalArgumentException> { DeclineReason(DeclineCategory.OTHER, "") }
        shouldThrow<IllegalArgumentException> {
            DeclineReason(DeclineCategory.OTHER, "x".repeat(DeclineReason.MAX_TEXT_LENGTH + 1))
        }
    }

    @Test
    fun `a page is never smaller than its items`() {
        shouldThrow<IllegalArgumentException> { ApplicationPage(listOf(1, 2), total = 1) }
    }
}
