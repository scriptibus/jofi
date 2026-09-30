// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.applications.domain.Amount
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationValidation
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.DeclineReason
import io.github.scriptibus.jofi.applications.domain.DeclineReasonInput
import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.EstimateConfidence
import io.github.scriptibus.jofi.applications.domain.FormOfAddress
import io.github.scriptibus.jofi.applications.domain.HowApplied
import io.github.scriptibus.jofi.applications.domain.LanguageAndToneInput
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
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.KClass

/**
 * The `application` table on a real PostgreSQL migrated from zero: its constraints mirror the domain
 * invariants without being stricter (ADR-0041), and each has a name of its own.
 */
class ApplicationSchemaTest {
    private lateinit var dsl: DSLContext
    private lateinit var rows: ApplicationRows
    private lateinit var company: UUID
    private val applicationId = UUID.randomUUID()

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        rows = ApplicationRows(dsl)
        company = rows.company()
    }

    @Test
    fun `stores a minimal application with its defaults`() {
        insert()

        val stored = dsl.selectFrom(APPLICATION).fetchSingle()
        stored.version shouldBe 0L
        stored.unread shouldBe false
        stored.companyId shouldBe company
    }

    @ParameterizedTest
    @MethodSource("enumColumns")
    fun `stores every constant of every domain enum`(
        type: KClass<out Enum<*>>,
        column: String,
    ) {
        type.java.enumConstants.forEach { constant ->
            insert(UUID.randomUUID()) {
                set(APPLICATION.field(column, String::class.java), constant.name)
                when {
                    column == "pay_estimate_confidence" -> payBand(source = "ESTIMATED", basis = "Basis")
                    constant.name == "ESTIMATED" -> payBand(basis = "Basis", confidence = "LOW")
                    column.startsWith("pay_") -> payBand()
                    column.startsWith("offer_salary") -> offerSalary()
                }
            }
        }
        dsl.fetchCount(APPLICATION) shouldBe type.java.enumConstants.size
    }

    @Test
    fun `accepts every value at exactly its domain limit`() {
        insert {
            title = "t".repeat(ApplicationDetails.MAX_TITLE_LENGTH)
            location = "l".repeat(ApplicationDetails.MAX_LOCATION_LENGTH)
            remoteShare = RemoteShare.MAX_PERCENT.toShort()
            portalNotes = "n".repeat(ApplicationDetails.MAX_NOTES_LENGTH)
            payBand(Amount.MAX, Amount.MAX, "ESTIMATED", "b".repeat(PaySource.Estimated.MAX_BASIS_LENGTH), "HIGH")
            postingLanguage = "de" + "-abcdefgh".repeat(3) + "-abcde"
            applicationLanguage = postingLanguage
            declineCategory = "OTHER"
            declineReason = "d".repeat(DeclineReason.MAX_TEXT_LENGTH)
            offerSalary(Amount.MAX)
            offerBonus = "b".repeat(OfferDetails.MAX_TEXT_LENGTH)
            offerBenefits = "b".repeat(OfferDetails.MAX_TEXT_LENGTH)
            offerRemoteShare = RemoteShare.MAX_PERCENT.toShort()
            offerVacationDays = OfferDetails.MAX_VACATION_DAYS.toShort()
            offerNoticePeriod = "n".repeat(OfferDetails.MAX_NOTICE_PERIOD_LENGTH)
            wantScore = BigDecimal.valueOf(Score.MAX_TENTHS.toLong(), 1)
            fitScore = BigDecimal("0.0")
        }

        dsl.selectFrom(APPLICATION).fetchSingle().payMax shouldBe Amount.MAX
    }

    @Test
    fun `stores whatever the domain accepts`() {
        val details = accepted.validate().shouldBeInstanceOf<ApplicationValidation.Valid<ApplicationDetails>>().value
        val band = details.payBand.shouldBeInstanceOf<PayBand>()
        val estimate = band.source.shouldBeInstanceOf<PaySource.Estimated>()
        val salary = details.offer?.salary.shouldBeInstanceOf<Pay>()

        insert {
            title = details.title
            location = details.location
            portalNotes = details.portalNotes
            payCurrency = band.currency.value
            payPeriod = band.period.name
            payBand(band.min, band.max, "ESTIMATED", estimate.basis, estimate.confidence.name)
            postingLanguage = details.languageAndTone.postingLanguage?.value
            applicationLanguage = details.languageAndTone.applicationLanguage?.value
            declineCategory = details.declineReason?.category?.name
            declineReason = details.declineReason?.text
            offerSalaryPeriod = salary.period.name
            offerSalary(salary.amount, salary.currency.value)
            offerNoticePeriod = details.offer?.noticePeriod
        }

        val stored = dsl.selectFrom(APPLICATION).fetchSingle()
        stored.payMin shouldBe band.min
        stored.offerSalary shouldBe salary.amount
        stored.applicationLanguage shouldBe "zh-Hant-TW"
    }

    @Test
    fun `every constraint has a name of its own`() {
        rows.constraintsOf("application") shouldBe CONSTRAINTS
    }

    @Test
    fun `rejects texts, numbers, versions and times the domain rejects`() {
        rejects("application_title_valid") { insert { title = " " } }
        rejects("application_title_valid") { insert { title = "x".repeat(ApplicationDetails.MAX_TITLE_LENGTH + 1) } }
        rejects("application_location_valid") { insert { location = "Berlin\n" } }
        rejects("application_portal_notes_valid") {
            insert { portalNotes = "x".repeat(ApplicationDetails.MAX_NOTES_LENGTH + 1) }
        }
        rejects("application_remote_share_valid") { insert { remoteShare = 101 } }
        rejects("application_offer_remote_share_valid") { insert { offerRemoteShare = -1 } }
        rejects("application_offer_vacation_days_valid") { insert { offerVacationDays = 367 } }
        rejects("application_offer_bonus_valid") { insert { offerBonus = "" } }
        rejects("application_offer_notice_period_valid") {
            insert { offerNoticePeriod = "x".repeat(OfferDetails.MAX_NOTICE_PERIOD_LENGTH + 1) }
        }
        rejects("application_want_score_valid") { insert { wantScore = BigDecimal("5.1") } }
        rejects("application_fit_score_valid") { insert { fitScore = BigDecimal("-0.1") } }
        rejects("application_version_valid") { insert { version = -1 } }
        rejects("application_updated_after_created") { insert { updatedAt = NOW.minusSeconds(1) } }
        rejects("application_employment_type_valid") { insert { employmentType = "full_time" } }
        dsl.fetchCount(APPLICATION) shouldBe 0
    }

    @Test
    fun `rejects pay bands, offer salaries and reasons the domain rejects`() {
        rejects("application_pay_band_complete") { insert { payCurrency = "EUR" } }
        rejects("application_pay_band_complete") { insert { payBand(min = null) } }
        rejects("application_pay_band_ordered") { insert { payBand(min = BigDecimal("2.00"), max = BigDecimal.ONE) } }
        rejects("application_pay_min_valid") { insert { payBand(min = BigDecimal("-0.01")) } }
        rejects("application_pay_currency_valid") {
            insert {
                payCurrency = "eur"
                payBand()
            }
        }
        rejects("application_pay_estimate_complete") { insert { payBand(source = "ESTIMATED") } }
        rejects("application_pay_estimate_complete") { insert { payBand(basis = "Basis", confidence = "LOW") } }
        rejects("application_pay_estimate_complete") { insert { payBand(source = "ESTIMATED", basis = "Basis") } }
        rejects("application_pay_estimate_basis_valid") {
            insert {
                payBand(
                    source = "ESTIMATED",
                    basis = "x".repeat(PaySource.Estimated.MAX_BASIS_LENGTH + 1),
                    confidence = "LOW",
                )
            }
        }
        rejects("application_offer_salary_complete") { insert { offerSalary = BigDecimal.TEN } }
        rejects("application_offer_salary_currency_valid") { insert { offerSalary(currency = "EURO") } }
        rejects("application_decline_reason_needs_category") { insert { declineReason = "Why" } }
        dsl.fetchCount(APPLICATION) shouldBe 0
    }

    @Test
    fun `rejects language tags the domain rejects`() {
        rejects("application_posting_language_valid") { insert { postingLanguage = "deutsch" } }
        rejects("application_posting_language_valid") { insert { postingLanguage = "de_DE" } }
        rejects("application_application_language_valid") { insert { applicationLanguage = "dé" } }
        rejects(
            "application_application_language_valid",
        ) { insert { applicationLanguage = "de" + "-abcdefgh".repeat(4) } }
    }

    @Test
    fun `PostgreSQL cannot store U+0000, which is why the domain rejects it`() {
        shouldThrow<DataAccessException> { insert { title = "Back\u0000end" } }
        ApplicationInput(
            "Back\u0000end",
            CompanyRef(company),
        ).validate().shouldBeInstanceOf<ApplicationValidation.Invalid>()
    }

    private val accepted
        get() =
            ApplicationInput(
                "Entwickler:in für İstanbul & Zürich 🚀",
                CompanyRef(company),
                "istanbul",
                0,
                EmploymentType.OTHER,
                Seniority.ENTRY,
                LocalDate.parse("2026-12-31"),
                HowApplied.OTHER,
                " Straße\n\n# Portal",
                PayBandInput(
                    BigDecimal.ZERO,
                    null,
                    "chf",
                    PayPeriod.HOUR,
                    PaySourceKind.ESTIMATED,
                    "Glassdoor, Zürich",
                    EstimateConfidence.LOW,
                ),
                LanguageAndToneInput("gsw-CH", "zh-Hant-TW", FormOfAddress.NEUTRAL, Tone.PROFESSIONAL),
                DeclineReasonInput(DeclineCategory.NO_REASON_GIVEN, "Keine Rückmeldung"),
                OfferInput(
                    PayInput(BigDecimal("0.5"), "EUR", PayPeriod.DAY),
                    noticePeriod = "3 Monate zum Quartalsende",
                ),
            )

    private fun insert(
        id: UUID = applicationId,
        customize: ApplicationRecord.() -> Unit = {},
    ) = rows.application(id, company, customize)

    companion object {
        private val CONSTRAINTS =
            listOf(
                "application_application_language_valid",
                "application_company_fk",
                "application_decline_category_valid",
                "application_decline_reason_needs_category",
                "application_decline_reason_valid",
                "application_employment_type_valid",
                "application_fit_score_valid",
                "application_form_of_address_valid",
                "application_how_applied_valid",
                "application_location_valid",
                "application_offer_benefits_valid",
                "application_offer_bonus_valid",
                "application_offer_notice_period_valid",
                "application_offer_remote_share_valid",
                "application_offer_salary_complete",
                "application_offer_salary_currency_valid",
                "application_offer_salary_period_valid",
                "application_offer_salary_valid",
                "application_offer_vacation_days_valid",
                "application_pay_band_complete",
                "application_pay_band_ordered",
                "application_pay_currency_valid",
                "application_pay_estimate_basis_valid",
                "application_pay_estimate_complete",
                "application_pay_estimate_confidence_valid",
                "application_pay_max_valid",
                "application_pay_min_valid",
                "application_pay_period_valid",
                "application_pay_source_valid",
                "application_pk",
                "application_portal_notes_valid",
                "application_posting_language_valid",
                "application_remote_share_valid",
                "application_seniority_valid",
                "application_title_valid",
                "application_tone_valid",
                "application_updated_after_created",
                "application_version_valid",
                "application_want_score_valid",
            )

        @JvmStatic
        fun enumColumns(): List<Arguments> =
            listOf(
                Arguments.of(EmploymentType::class, "employment_type"),
                Arguments.of(Seniority::class, "seniority"),
                Arguments.of(HowApplied::class, "how_applied"),
                Arguments.of(PayPeriod::class, "pay_period"),
                Arguments.of(PaySourceKind::class, "pay_source"),
                Arguments.of(EstimateConfidence::class, "pay_estimate_confidence"),
                Arguments.of(PayPeriod::class, "offer_salary_period"),
                Arguments.of(FormOfAddress::class, "form_of_address"),
                Arguments.of(Tone::class, "tone"),
                Arguments.of(DeclineCategory::class, "decline_category"),
            )
    }
}
