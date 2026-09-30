// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.CurrencyCode
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.DeclineReason
import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.EstimateConfidence
import io.github.scriptibus.jofi.applications.domain.FormOfAddress
import io.github.scriptibus.jofi.applications.domain.HowApplied
import io.github.scriptibus.jofi.applications.domain.LanguageAndTone
import io.github.scriptibus.jofi.applications.domain.LanguageAndToneInput
import io.github.scriptibus.jofi.applications.domain.LanguageTag
import io.github.scriptibus.jofi.applications.domain.OfferDetails
import io.github.scriptibus.jofi.applications.domain.OfferInput
import io.github.scriptibus.jofi.applications.domain.Pay
import io.github.scriptibus.jofi.applications.domain.PayBand
import io.github.scriptibus.jofi.applications.domain.PayBandInput
import io.github.scriptibus.jofi.applications.domain.PayInput
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySource
import io.github.scriptibus.jofi.applications.domain.PaySourceKind
import io.github.scriptibus.jofi.applications.domain.RemoteShare
import io.github.scriptibus.jofi.applications.domain.Score
import io.github.scriptibus.jofi.applications.domain.Seniority
import io.github.scriptibus.jofi.applications.domain.Tone
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.KClass

class ApplicationDtosTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val uuid = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val companyUuid = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val contactUuid = UUID.fromString("00000000-0000-0000-0000-0000000000c1")

    @ParameterizedTest
    @MethodSource("enumPairs")
    fun `every API enum has exactly the constants of its domain enum`(
        api: KClass<out Enum<*>>,
        domain: KClass<out Enum<*>>,
    ) {
        api.java.enumConstants.map { it.name } shouldBe domain.java.enumConstants.map { it.name }
    }

    private val fullRequest =
        ApplicationDetailsRequest(
            title = " Backend Engineer ",
            companyId = companyUuid,
            location = "Berlin",
            remoteShare = 120,
            employmentType = JobEmploymentType.WORKING_STUDENT,
            seniority = JobSeniority.JUNIOR,
            deadline = LocalDate.parse("2026-10-31"),
            howApplied = ApplyChannel.REFERRAL,
            portalNotes = "Notes",
            payBand =
                PayBandDto(
                    BigDecimal("1.234"),
                    null,
                    "eur",
                    PayInterval.HOUR,
                    PayBandSource.ESTIMATED,
                    "Basis",
                    PayEstimateConfidence.LOW,
                ),
            languageAndTone = LanguageAndToneDto("de", " en ", AddressForm.SIE, WritingTone.PROFESSIONAL),
            offer = OfferDto(PayDto(BigDecimal.TEN, "EUR", PayInterval.MONTH), bonus = "10 %", vacationDays = 30),
        )

    private val fullInput =
        ApplicationInput(
            " Backend Engineer ",
            CompanyRef(companyUuid),
            "Berlin",
            120,
            EmploymentType.WORKING_STUDENT,
            Seniority.JUNIOR,
            LocalDate.parse("2026-10-31"),
            HowApplied.REFERRAL,
            "Notes",
            PayBandInput(
                BigDecimal("1.234"),
                null,
                "eur",
                PayPeriod.HOUR,
                PaySourceKind.ESTIMATED,
                "Basis",
                EstimateConfidence.LOW,
            ),
            LanguageAndToneInput("de", " en ", FormOfAddress.SIE, Tone.PROFESSIONAL),
            OfferInput(PayInput(BigDecimal.TEN, "EUR", PayPeriod.MONTH), bonus = "10 %", vacationDays = 30),
        )

    @Test
    fun `requests become domain input unchanged, validation is the domain's job`() {
        fullRequest.toInput() shouldBe fullInput
        ApplicationDetailsRequest("X", companyUuid).toInput() shouldBe ApplicationInput("X", CompanyRef(companyUuid))
    }

    private val amount = BigDecimal("85000.00")
    private val application =
        Application(
            ApplicationId(uuid),
            ApplicationDetails(
                title = "Backend Engineer",
                company = CompanyRef(companyUuid),
                location = "Berlin",
                remoteShare = RemoteShare(40),
                employmentType = EmploymentType.FULL_TIME,
                seniority = Seniority.SENIOR,
                deadline = LocalDate.parse("2026-10-31"),
                howApplied = HowApplied.PORTAL,
                portalNotes = "Secret portal notes",
                payBand = PayBand(null, amount, CurrencyCode("EUR"), PayPeriod.YEAR, PaySource.Recruiter),
                languageAndTone = LanguageAndTone(LanguageTag("de"), null, FormOfAddress.DU, Tone.PERSONAL),
                offer = OfferDetails(Pay(amount, CurrencyCode("EUR"), PayPeriod.YEAR), benefits = "Secret benefits"),
            ),
            contacts = setOf(ContactRef(contactUuid)),
            unread = true,
            wantScore = Score(35),
            fitScore = null,
            status = ApplicationStatus.REJECTED,
            declineReason = DeclineReason(DeclineCategory.SALARY, "Secret reason"),
            version = 2,
            createdAt = at,
            updatedAt = at.plusSeconds(60),
        )

    @Test
    fun `an application becomes a response with every field`() {
        val response = ApplicationResponse.from(application)

        response shouldBe
            ApplicationResponse(
                id = uuid,
                title = "Backend Engineer",
                companyId = companyUuid,
                location = "Berlin",
                remoteShare = 40,
                employmentType = JobEmploymentType.FULL_TIME,
                seniority = JobSeniority.SENIOR,
                deadline = LocalDate.parse("2026-10-31"),
                howApplied = ApplyChannel.PORTAL,
                portalNotes = "Secret portal notes",
                payBand = PayBandDto(null, amount, "EUR", PayInterval.YEAR, PayBandSource.RECRUITER),
                languageAndTone = LanguageAndToneDto("de", null, AddressForm.DU, WritingTone.PERSONAL),
                offer = OfferDto(PayDto(amount, "EUR", PayInterval.YEAR), benefits = "Secret benefits"),
                status = PipelineStatus.REJECTED,
                declineReason = DeclineReasonDto(DeclineReasonCategory.SALARY, "Secret reason"),
                contactIds = listOf(contactUuid),
                unread = true,
                wantScore = BigDecimal("3.5"),
                fitScore = null,
                version = 2,
                createdAt = at,
                updatedAt = at.plusSeconds(60),
            )
        ApplicationPageResponse.from(ApplicationPage(listOf(application), total = 5), 0, 1) shouldBe
            ApplicationPageResponse(listOf(response), page = 0, size = 1, total = 5)
    }

    @Test
    fun `an estimated pay band keeps its basis and confidence`() {
        val band =
            PayBand(
                amount,
                null,
                CurrencyCode("CHF"),
                PayPeriod.MONTH,
                PaySource.Estimated("Basis", EstimateConfidence.HIGH),
            )

        PayBandDto.from(band) shouldBe
            PayBandDto(
                amount,
                null,
                "CHF",
                PayInterval.MONTH,
                PayBandSource.ESTIMATED,
                "Basis",
                PayEstimateConfidence.HIGH,
            )
        PayBandDto.from(band.copy(source = PaySource.Posting)).source shouldBe PayBandSource.POSTING
    }

    @Test
    fun `DTOs print none of the user's notes`() {
        val response = ApplicationResponse.from(application)
        listOf(response, response.declineReason, response.offer, ApplicationDetailsRequest("Secret title", companyUuid))
            .forEach { it.toString() shouldNotContain "Secret" }
        val estimate = PayBandDto(amount, amount, "EUR", PayInterval.YEAR, PayBandSource.ESTIMATED, "Secret basis")
        listOf(estimate, PayDto(amount, "EUR", PayInterval.YEAR)).forEach {
            it.toString() shouldNotContain "85000"
            it.toString() shouldNotContain "Secret"
        }
    }

    companion object {
        @JvmStatic
        fun enumPairs(): List<Arguments> =
            listOf(
                Arguments.of(JobEmploymentType::class, EmploymentType::class),
                Arguments.of(JobSeniority::class, Seniority::class),
                Arguments.of(ApplyChannel::class, HowApplied::class),
                Arguments.of(PayInterval::class, PayPeriod::class),
                Arguments.of(PayBandSource::class, PaySourceKind::class),
                Arguments.of(PayEstimateConfidence::class, EstimateConfidence::class),
                Arguments.of(AddressForm::class, FormOfAddress::class),
                Arguments.of(WritingTone::class, Tone::class),
                Arguments.of(DeclineReasonCategory::class, DeclineCategory::class),
                Arguments.of(PipelineStatus::class, ApplicationStatus::class),
            )
    }
}
