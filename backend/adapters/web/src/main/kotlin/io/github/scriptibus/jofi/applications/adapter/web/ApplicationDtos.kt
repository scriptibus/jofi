// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.DeclineReason
import io.github.scriptibus.jofi.applications.domain.DeclineReasonInput
import io.github.scriptibus.jofi.applications.domain.LanguageAndTone
import io.github.scriptibus.jofi.applications.domain.LanguageAndToneInput
import io.github.scriptibus.jofi.applications.domain.OfferDetails
import io.github.scriptibus.jofi.applications.domain.OfferInput
import io.github.scriptibus.jofi.applications.domain.Pay
import io.github.scriptibus.jofi.applications.domain.PayBand
import io.github.scriptibus.jofi.applications.domain.PayBandInput
import io.github.scriptibus.jofi.applications.domain.PayInput
import io.github.scriptibus.jofi.applications.domain.PaySource
import io.github.scriptibus.jofi.applications.domain.Score
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Notes and reasons are the user's own words: DTOs that hold them print none of it.

/**
 * What the user edits about an application. Text is trimmed and blank optional text counts as absent;
 * a violation answers 400 naming the request field (`title`, `payBand.max`, `offer.salary.currency`).
 */
data class ApplicationDetailsRequest(
    val title: String,
    /** The company; one that does not exist is a 400 (`companyId`, `NOT_FOUND`). */
    val companyId: UUID,
    val location: String? = null,
    /** Percent of remote work, 0 to 100. */
    val remoteShare: Int? = null,
    val employmentType: JobEmploymentType? = null,
    val seniority: JobSeniority? = null,
    val deadline: LocalDate? = null,
    val howApplied: ApplyChannel? = null,
    /** Markdown. */
    val portalNotes: String? = null,
    val payBand: PayBandDto? = null,
    val languageAndTone: LanguageAndToneDto? = null,
    val declineReason: DeclineReasonDto? = null,
    val offer: OfferDto? = null,
) {
    fun toInput(): ApplicationInput =
        ApplicationInput(
            title = title,
            company = CompanyRef(companyId),
            location = location,
            remoteShare = remoteShare,
            employmentType = employmentType?.mapByName(),
            seniority = seniority?.mapByName(),
            deadline = deadline,
            howApplied = howApplied?.mapByName(),
            portalNotes = portalNotes,
            payBand = payBand?.toInput(),
            languageAndTone = languageAndTone?.toInput(),
            declineReason = declineReason?.toInput(),
            offer = offer?.toInput(),
        )

    override fun toString(): String = "ApplicationDetailsRequest(companyId=$companyId)"
}

/**
 * A pay band: gross [min] and/or [max] (two decimals at most) in [currency] (ISO 4217) per [period].
 * [estimateBasis] and [estimateConfidence] are required for `ESTIMATED` and absent otherwise.
 */
data class PayBandDto(
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    val currency: String,
    val period: PayInterval,
    val source: PayBandSource,
    val estimateBasis: String? = null,
    val estimateConfidence: PayEstimateConfidence? = null,
) {
    fun toInput(): PayBandInput =
        PayBandInput(
            min,
            max,
            currency,
            period.mapByName(),
            source.mapByName(),
            estimateBasis,
            estimateConfidence?.mapByName(),
        )

    override fun toString(): String = "PayBandDto(currency=$currency, period=$period, source=$source)"

    companion object {
        fun from(band: PayBand): PayBandDto {
            val estimate = band.source as? PaySource.Estimated
            val source =
                when (band.source) {
                    PaySource.Posting -> PayBandSource.POSTING
                    PaySource.Recruiter -> PayBandSource.RECRUITER
                    is PaySource.Estimated -> PayBandSource.ESTIMATED
                }
            return PayBandDto(
                band.min,
                band.max,
                band.currency.value,
                band.period.mapByName(),
                source,
                estimate?.basis,
                estimate?.confidence?.mapByName(),
            )
        }
    }
}

/** A gross amount in [currency] (ISO 4217) per [period]. */
data class PayDto(
    val amount: BigDecimal,
    val currency: String,
    val period: PayInterval,
) {
    fun toInput(): PayInput = PayInput(amount, currency, period.mapByName())

    override fun toString(): String = "PayDto(currency=$currency, period=$period)"

    companion object {
        fun from(pay: Pay): PayDto = PayDto(pay.amount, pay.currency.value, pay.period.mapByName())
    }
}

/**
 * Language tags are BCP 47 (`de`, `en-GB`). A null [applicationLanguage] follows [postingLanguage], so
 * the language to apply in is `applicationLanguage ?: postingLanguage`.
 */
data class LanguageAndToneDto(
    val postingLanguage: String? = null,
    val applicationLanguage: String? = null,
    val formOfAddress: AddressForm? = null,
    val tone: WritingTone? = null,
) {
    fun toInput(): LanguageAndToneInput =
        LanguageAndToneInput(postingLanguage, applicationLanguage, formOfAddress?.mapByName(), tone?.mapByName())

    companion object {
        fun from(value: LanguageAndTone): LanguageAndToneDto =
            LanguageAndToneDto(
                value.postingLanguage?.value,
                value.applicationLanguage?.value,
                value.formOfAddress?.mapByName(),
                value.tone?.mapByName(),
            )
    }
}

/** Why the user declined or the company rejected; [text] is Markdown. */
data class DeclineReasonDto(
    val category: DeclineReasonCategory,
    val text: String? = null,
) {
    fun toInput(): DeclineReasonInput = DeclineReasonInput(category.mapByName(), text)

    override fun toString(): String = "DeclineReasonDto(category=$category)"

    companion object {
        fun from(reason: DeclineReason): DeclineReasonDto = DeclineReasonDto(reason.category.mapByName(), reason.text)
    }
}

/** What the company offered; an offer without any field counts as none. */
data class OfferDto(
    val salary: PayDto? = null,
    val bonus: String? = null,
    val benefits: String? = null,
    /** Percent of remote work, 0 to 100. */
    val remoteShare: Int? = null,
    val vacationDays: Int? = null,
    val noticePeriod: String? = null,
    val startDate: LocalDate? = null,
    val answerBy: LocalDate? = null,
) {
    fun toInput(): OfferInput =
        OfferInput(salary?.toInput(), bonus, benefits, remoteShare, vacationDays, noticePeriod, startDate, answerBy)

    override fun toString(): String = "OfferDto(startDate=$startDate, answerBy=$answerBy)"

    companion object {
        fun from(offer: OfferDetails): OfferDto =
            OfferDto(
                offer.salary?.let(PayDto::from),
                offer.bonus,
                offer.benefits,
                offer.remoteShare?.percent,
                offer.vacationDays,
                offer.noticePeriod,
                offer.startDate,
                offer.answerBy,
            )
    }
}

/** Body of `PUT /api/applications/{id}`; [basedOnVersion] is the `version` the client last read. */
data class UpdateApplicationRequest(
    val details: ApplicationDetailsRequest,
    val basedOnVersion: Long,
)

/** Body of `PUT /api/applications/{id}/unread`. */
data class ApplicationUnreadRequest(
    val unread: Boolean,
)

/**
 * Body of `PUT /api/applications/{id}/contacts`: exactly the contacts to link (at most 50); to link or
 * unlink one, send the changed list with the version last read.
 */
data class ApplicationContactsRequest(
    val contactIds: List<UUID>,
    val basedOnVersion: Long,
)

/**
 * One application. [version] goes back as `basedOnVersion` with the next change; render the notes
 * sanitised. Scores are 0 to 5 with one decimal (null until scoring exists).
 */
data class ApplicationResponse(
    val id: UUID,
    val title: String,
    val companyId: UUID,
    val location: String?,
    val remoteShare: Int?,
    val employmentType: JobEmploymentType?,
    val seniority: JobSeniority?,
    val deadline: LocalDate?,
    val howApplied: ApplyChannel?,
    val portalNotes: String?,
    val payBand: PayBandDto?,
    val languageAndTone: LanguageAndToneDto,
    val declineReason: DeclineReasonDto?,
    val offer: OfferDto?,
    val contactIds: List<UUID>,
    val unread: Boolean,
    val wantScore: BigDecimal?,
    val fitScore: BigDecimal?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "ApplicationResponse(id=$id, version=$version)"

    companion object {
        fun from(application: Application): ApplicationResponse =
            with(application.details) {
                ApplicationResponse(
                    application.id.value,
                    title,
                    company.value,
                    location,
                    remoteShare?.percent,
                    employmentType?.mapByName(),
                    seniority?.mapByName(),
                    deadline,
                    howApplied?.mapByName(),
                    portalNotes,
                    payBand?.let(PayBandDto::from),
                    LanguageAndToneDto.from(languageAndTone),
                    declineReason?.let(DeclineReasonDto::from),
                    offer?.let(OfferDto::from),
                    application.contacts.map { it.value }.sorted(),
                    application.unread,
                    application.wantScore?.decimal(),
                    application.fitScore?.decimal(),
                    application.version,
                    application.createdAt,
                    application.updatedAt,
                )
            }

        private fun Score.decimal(): BigDecimal = BigDecimal.valueOf(tenths.toLong(), 1)
    }
}

/** JSON body of `GET /api/applications`: one page of applications. */
data class ApplicationPageResponse(
    val applications: List<ApplicationResponse>,
    val page: Int,
    val size: Int,
    /** All applications matching the search, across pages. */
    val total: Long,
) {
    companion object {
        fun from(
            page: ApplicationPage<Application>,
            number: Int,
            size: Int,
        ): ApplicationPageResponse =
            ApplicationPageResponse(page.items.map(ApplicationResponse::from), number, size, page.total)
    }
}
