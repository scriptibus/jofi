// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.EstimateConfidence
import io.github.scriptibus.jofi.applications.domain.FormOfAddress
import io.github.scriptibus.jofi.applications.domain.HowApplied
import io.github.scriptibus.jofi.applications.domain.LanguageAndTone
import io.github.scriptibus.jofi.applications.domain.OfferDetails
import io.github.scriptibus.jofi.applications.domain.Pay
import io.github.scriptibus.jofi.applications.domain.PayBand
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySource
import io.github.scriptibus.jofi.applications.domain.PaySourceKind
import io.github.scriptibus.jofi.applications.domain.Score
import io.github.scriptibus.jofi.applications.domain.Seniority
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.applications.domain.Tone
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The application tools' results. What a posting says (title, location, where it was found) is
 * [Untrusted]: imports and scanners copy it from third-party pages. The user's own fields stay plain.
 */
data class ApplicationSearchResult(
    val total: Long,
    val page: Int,
    val size: Int,
    val applications: List<ApplicationSummary>,
) {
    companion object {
        fun from(
            page: ApplicationPage<Application>,
            number: Int,
            size: Int,
        ) = ApplicationSearchResult(page.total, number, size, page.items.map(ApplicationSummary::from))
    }
}

data class ApplicationSummary(
    val id: UUID,
    val companyId: UUID,
    val status: ApplicationStatus,
    val unread: Boolean,
    val deadline: LocalDate?,
    val updatedAt: Instant,
    val posting: Untrusted<PostingSummary>,
) {
    companion object {
        fun from(application: Application) =
            ApplicationSummary(
                application.id.value,
                application.details.company.value,
                application.status,
                application.unread,
                application.details.deadline,
                application.updatedAt,
                Untrusted(PostingSummary(application.details.title, application.details.location)),
            )
    }
}

data class PostingSummary(
    val title: String,
    val location: String?,
)

/**
 * One application in full, except its scores' history and the status history, which no tool needs yet. The posting's
 * title, location and sources are [Untrusted] (imports and scanners copy them from pages), and so is every other
 * text a tool can write ([notes]); [update_application][UpdateApplicationTool] takes all of it back.
 */
data class ApplicationDetailResult(
    val id: UUID,
    val version: Long,
    val companyId: UUID,
    val contactIds: List<UUID>,
    val status: ApplicationStatus,
    val declineCategory: DeclineCategory?,
    val unread: Boolean,
    val remoteSharePercent: Int?,
    val employmentType: EmploymentType?,
    val seniority: Seniority?,
    val deadline: LocalDate?,
    val howApplied: HowApplied?,
    val payBand: PayBandResult?,
    val languageAndTone: LanguageAndToneResult,
    val offer: OfferResult?,
    val wantScore: BigDecimal?,
    val fitScore: BigDecimal?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val posting: Untrusted<PostingDetails>,
    val notes: Untrusted<ApplicationNotes>,
) {
    companion object {
        fun from(application: Application): ApplicationDetailResult {
            val details = application.details
            return ApplicationDetailResult(
                application.id.value,
                application.version,
                details.company.value,
                application.contacts.map { it.value }.sorted(),
                application.status,
                application.declineReason?.category,
                application.unread,
                details.remoteShare?.percent,
                details.employmentType,
                details.seniority,
                details.deadline,
                details.howApplied,
                details.payBand?.let(PayBandResult::from),
                LanguageAndToneResult.from(details.languageAndTone),
                details.offer?.let(OfferResult::from),
                application.wantScore?.let(::points),
                application.fitScore?.let(::points),
                application.createdAt,
                application.updatedAt,
                Untrusted(PostingDetails(details.title, details.location, application.sources.map(SourceResult::from))),
                Untrusted(notesOf(application)),
            )
        }

        private fun notesOf(application: Application): ApplicationNotes {
            val details = application.details
            return ApplicationNotes(
                details.portalNotes,
                (details.payBand?.source as? PaySource.Estimated)?.basis,
                application.declineReason?.text,
                details.offer?.let { OfferTexts(it.bonus, it.benefits, it.noticePeriod) },
            )
        }

        private fun points(score: Score): BigDecimal = BigDecimal.valueOf(score.tenths.toLong(), 1)
    }
}

/** The texts of an application besides its posting: what `update_application` takes back as plain arguments. */
data class ApplicationNotes(
    val portalNotes: String?,
    val payEstimateBasis: String?,
    val declineReason: String?,
    val offer: OfferTexts?,
)

data class OfferTexts(
    val bonus: String?,
    val benefits: String?,
    val noticePeriod: String?,
)

data class LanguageAndToneResult(
    val postingLanguage: String?,
    val applicationLanguage: String?,
    val formOfAddress: FormOfAddress?,
    val tone: Tone?,
) {
    companion object {
        fun from(languageAndTone: LanguageAndTone) =
            LanguageAndToneResult(
                languageAndTone.postingLanguage?.value,
                languageAndTone.applicationLanguage?.value,
                languageAndTone.formOfAddress,
                languageAndTone.tone,
            )
    }
}

/** The typed part of an offer; its texts are in [ApplicationNotes]. */
data class OfferResult(
    val salary: PayResult?,
    val remoteSharePercent: Int?,
    val vacationDays: Int?,
    val startDate: LocalDate?,
    val answerBy: LocalDate?,
) {
    companion object {
        fun from(offer: OfferDetails) =
            OfferResult(
                offer.salary?.let(PayResult::from),
                offer.remoteShare?.percent,
                offer.vacationDays,
                offer.startDate,
                offer.answerBy,
            )
    }
}

data class PayResult(
    val amount: BigDecimal,
    val currency: String,
    val period: PayPeriod,
) {
    companion object {
        fun from(pay: Pay) = PayResult(pay.amount, pay.currency.value, pay.period)
    }
}

data class PostingDetails(
    val title: String,
    val location: String?,
    val sources: List<SourceResult>,
)

data class SourceResult(
    val kind: SourceKind,
    val url: String?,
    val discoveredAt: Instant,
    val offlineSince: Instant?,
) {
    companion object {
        fun from(source: ApplicationSource) =
            SourceResult(source.kind, source.originalUrl?.value, source.discoveredAt, source.offlineSince)
    }
}

data class PayBandResult(
    val min: BigDecimal?,
    val max: BigDecimal?,
    val currency: String,
    val period: PayPeriod,
    val source: PaySourceKind,
    val estimateConfidence: EstimateConfidence?,
) {
    companion object {
        fun from(band: PayBand) =
            PayBandResult(
                band.min,
                band.max,
                band.currency.value,
                band.period,
                when (band.source) {
                    PaySource.Posting -> PaySourceKind.POSTING
                    PaySource.Recruiter -> PaySourceKind.RECRUITER
                    is PaySource.Estimated -> PaySourceKind.ESTIMATED
                },
                (band.source as? PaySource.Estimated)?.confidence,
            )
    }
}
