// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.normalizedText
import java.math.BigDecimal
import java.time.LocalDate

/** Result of validating untrusted application input: the domain value, or every problem found. */
sealed interface ApplicationValidation<out T> {
    data class Valid<out T>(
        val value: T,
    ) : ApplicationValidation<T>

    data class Invalid(
        val violations: List<ApplicationViolation>,
    ) : ApplicationValidation<Nothing> {
        init {
            require(violations.isNotEmpty()) { "An invalid input names at least one violation" }
        }
    }
}

/**
 * Application details as the user, the AI, a scanner or an external client entered them. [validate]
 * normalizes text to Unicode NFC and trims it, treats blank optional text as absent, upper-cases
 * currency codes, brings language tags into canonical case, gives amounts two decimals, and reports
 * what is still wrong. Whether [company] exists is the use case's check. [toString] leaves out the
 * user's notes.
 */
data class ApplicationInput(
    val title: String,
    val company: CompanyRef,
    val location: String? = null,
    /** Percent, 0 to 100. */
    val remoteShare: Int? = null,
    val employmentType: EmploymentType? = null,
    val seniority: Seniority? = null,
    val deadline: LocalDate? = null,
    val howApplied: HowApplied? = null,
    val portalNotes: String? = null,
    val payBand: PayBandInput? = null,
    val languageAndTone: LanguageAndToneInput? = null,
    val declineReason: DeclineReasonInput? = null,
    val offer: OfferInput? = null,
) {
    fun validate(): ApplicationValidation<ApplicationDetails> {
        val checks = InputChecks()
        val title = checks.requiredText(ApplicationField.TITLE, title, ApplicationDetails.MAX_TITLE_LENGTH)
        val location = checks.text(ApplicationField.LOCATION, location, ApplicationDetails.MAX_LOCATION_LENGTH)
        val remoteShare = checks.remoteShare(ApplicationField.REMOTE_SHARE, remoteShare)
        val portalNotes = checks.text(ApplicationField.PORTAL_NOTES, portalNotes, ApplicationDetails.MAX_NOTES_LENGTH)
        val payBand = payBand?.parse(checks)
        val languageAndTone = languageAndTone?.parse(checks) ?: LanguageAndTone.UNKNOWN
        val declineReason = declineReason?.parse(checks)
        val offer = offer?.parse(checks)
        if (title == null || checks.count > 0) return ApplicationValidation.Invalid(checks.violations)
        return ApplicationValidation.Valid(
            ApplicationDetails(
                title,
                company,
                location,
                remoteShare,
                employmentType,
                seniority,
                deadline,
                howApplied,
                portalNotes,
                payBand,
                languageAndTone,
                declineReason,
                offer,
            ),
        )
    }

    override fun toString(): String = "ApplicationInput(company=${company.value})"
}

/**
 * A pay band as entered. [estimateBasis] and [estimateConfidence] belong to [PaySourceKind.ESTIMATED]
 * (both required there) and are dropped for the other sources.
 */
data class PayBandInput(
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    val currency: String,
    val period: PayPeriod,
    val source: PaySourceKind,
    val estimateBasis: String? = null,
    val estimateConfidence: EstimateConfidence? = null,
) {
    internal fun parse(checks: InputChecks): PayBand? {
        val before = checks.count
        val min = checks.amount(ApplicationField.PAY_MIN, min)
        val max = checks.amount(ApplicationField.PAY_MAX, max)
        val currency = checks.currency(ApplicationField.PAY_CURRENCY, currency)
        if (this.min == null && this.max == null) checks.report(ApplicationField.PAY_MIN, ApplicationProblem.REQUIRED)
        if (min != null && max != null &&
            min > max
        ) {
            checks.report(ApplicationField.PAY_MAX, ApplicationProblem.MIN_ABOVE_MAX)
        }
        val source = source(checks)
        if (checks.count > before || currency == null || source == null) return null
        return PayBand(min, max, currency, period, source)
    }

    private fun source(checks: InputChecks): PaySource? =
        when (source) {
            PaySourceKind.POSTING -> PaySource.Posting
            PaySourceKind.RECRUITER -> PaySource.Recruiter
            PaySourceKind.ESTIMATED -> estimate(checks)
        }

    private fun estimate(checks: InputChecks): PaySource.Estimated? {
        val basis =
            checks.requiredText(
                ApplicationField.PAY_ESTIMATE_BASIS,
                estimateBasis.orEmpty(),
                PaySource.Estimated.MAX_BASIS_LENGTH,
            )
        if (estimateConfidence ==
            null
        ) {
            checks.report(ApplicationField.PAY_ESTIMATE_CONFIDENCE, ApplicationProblem.REQUIRED)
        }
        return if (basis != null && estimateConfidence != null) PaySource.Estimated(basis, estimateConfidence) else null
    }
}

/** Language and tone as entered; language tags are trimmed and brought into canonical case (`de`, `en-GB`). */
data class LanguageAndToneInput(
    val postingLanguage: String? = null,
    val applicationLanguage: String? = null,
    val formOfAddress: FormOfAddress? = null,
    val tone: Tone? = null,
) {
    internal fun parse(checks: InputChecks): LanguageAndTone =
        LanguageAndTone(
            checks.language(ApplicationField.POSTING_LANGUAGE, postingLanguage),
            checks.language(ApplicationField.APPLICATION_LANGUAGE, applicationLanguage),
            formOfAddress,
            tone,
        )
}

/** A decline or rejection reason as entered; [toString] leaves out the text. */
data class DeclineReasonInput(
    val category: DeclineCategory,
    val text: String? = null,
) {
    internal fun parse(checks: InputChecks): DeclineReason? {
        val before = checks.count
        val text = checks.text(ApplicationField.DECLINE_REASON_TEXT, text, DeclineReason.MAX_TEXT_LENGTH)
        return if (checks.count > before) null else DeclineReason(category, text)
    }

    override fun toString(): String = "DeclineReasonInput(category=$category)"
}

/** An offer as entered; an offer without any detail counts as no offer. [toString] leaves out the text. */
data class OfferInput(
    val salary: PayInput? = null,
    val bonus: String? = null,
    val benefits: String? = null,
    val remoteShare: Int? = null,
    val vacationDays: Int? = null,
    val noticePeriod: String? = null,
    val startDate: LocalDate? = null,
    val answerBy: LocalDate? = null,
) {
    internal fun parse(checks: InputChecks): OfferDetails? {
        val before = checks.count
        val salary = salary?.parse(checks)
        val bonus = checks.text(ApplicationField.OFFER_BONUS, bonus, OfferDetails.MAX_TEXT_LENGTH)
        val benefits = checks.text(ApplicationField.OFFER_BENEFITS, benefits, OfferDetails.MAX_TEXT_LENGTH)
        val remoteShare = checks.remoteShare(ApplicationField.OFFER_REMOTE_SHARE, remoteShare)
        val noticePeriod =
            checks.text(ApplicationField.OFFER_NOTICE_PERIOD, noticePeriod, OfferDetails.MAX_NOTICE_PERIOD_LENGTH)
        if (vacationDays != null && vacationDays !in 0..OfferDetails.MAX_VACATION_DAYS) {
            checks.report(ApplicationField.OFFER_VACATION_DAYS, ApplicationProblem.OUT_OF_RANGE)
        }
        val details = listOf(salary, bonus, benefits, remoteShare, vacationDays, noticePeriod, startDate, answerBy)
        if (checks.count > before || details.all { it == null }) return null
        return OfferDetails(salary, bonus, benefits, remoteShare, vacationDays, noticePeriod, startDate, answerBy)
    }

    override fun toString(): String = "OfferInput(startDate=$startDate, answerBy=$answerBy)"
}

/** One amount of money as entered, e.g. an offer's salary. */
data class PayInput(
    val amount: BigDecimal,
    val currency: String,
    val period: PayPeriod,
) {
    internal fun parse(checks: InputChecks): Pay? {
        val amount = checks.amount(ApplicationField.OFFER_SALARY, amount)
        val currency = checks.currency(ApplicationField.OFFER_SALARY_CURRENCY, currency)
        return if (amount != null && currency != null) Pay(amount, currency, period) else null
    }
}

/** Collects the violations of one input while its parts are parsed. */
internal class InputChecks {
    private val found = mutableListOf<ApplicationViolation>()

    val violations: List<ApplicationViolation> get() = found.toList()
    val count: Int get() = found.size

    fun report(
        field: ApplicationField,
        problem: ApplicationProblem?,
    ) {
        if (problem != null) found += ApplicationViolation(field, problem)
    }

    /** The trimmed text, or `null` if it is blank (absent) or broken (reported). */
    fun text(
        field: ApplicationField,
        raw: String?,
        maxLength: Int,
    ): String? {
        val text = raw?.normalizedText()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val problem = ApplicationRules.textProblemOf(text, maxLength)
        report(field, problem)
        return text.takeIf { problem == null }
    }

    fun requiredText(
        field: ApplicationField,
        raw: String,
        maxLength: Int,
    ): String? = text(field, raw, maxLength).also { if (raw.isBlank()) report(field, ApplicationProblem.REQUIRED) }

    fun remoteShare(
        field: ApplicationField,
        percent: Int?,
    ): RemoteShare? =
        when (percent) {
            null -> null
            in 0..RemoteShare.MAX_PERCENT -> RemoteShare(percent)
            else -> null.also { report(field, ApplicationProblem.OUT_OF_RANGE) }
        }

    fun amount(
        field: ApplicationField,
        amount: BigDecimal?,
    ): BigDecimal? {
        if (amount == null) return null
        val problem = Amount.problemOf(amount)
        report(field, problem)
        return if (problem == null) Amount.normalized(amount) else null
    }

    fun currency(
        field: ApplicationField,
        raw: String,
    ): CurrencyCode? {
        val code = raw.trim().uppercase()
        if (code.isEmpty()) report(field, ApplicationProblem.REQUIRED)
        if (code.isNotEmpty() && !CurrencyCode.isValid(code)) report(field, ApplicationProblem.INVALID_CURRENCY)
        return code.takeIf(CurrencyCode::isValid)?.let(::CurrencyCode)
    }

    fun language(
        field: ApplicationField,
        raw: String?,
    ): LanguageTag? {
        val tag = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!LanguageTag.isValid(tag)) report(field, ApplicationProblem.INVALID_LANGUAGE)
        return tag.takeIf(LanguageTag::isValid)?.let { LanguageTag(LanguageTag.canonical(it)) }
    }
}
