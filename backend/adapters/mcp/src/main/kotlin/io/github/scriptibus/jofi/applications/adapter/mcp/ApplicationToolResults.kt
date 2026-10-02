// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
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
 * One application in full. Everything `update_application` takes back sits at the top level, under the same names
 * and nesting: send the `content` of each untrusted object (`posting`, `notes`, `languageAndTone`) under its key.
 * What no tool can change sits under [readOnly], which is not sent back. The posting's title and location,
 * the notes and the language tags are [Untrusted]: imports and scanners copy them from pages, and tools can write them.
 */
data class ApplicationDetailResult(
    val id: UUID,
    val version: Long,
    val companyId: UUID,
    val remoteSharePercent: Int?,
    val employmentType: EmploymentType?,
    val seniority: Seniority?,
    val deadline: LocalDate?,
    val howApplied: HowApplied?,
    val payBand: PayBandResult?,
    val offer: OfferResult?,
    val languageAndTone: Untrusted<LanguageAndToneResult>,
    val posting: Untrusted<PostingFields>,
    val notes: Untrusted<ApplicationNotes>,
    val readOnly: ApplicationReadOnly,
) {
    companion object {
        fun from(application: Application): ApplicationDetailResult {
            val details = application.details
            return ApplicationDetailResult(
                application.id.value,
                application.version,
                details.company.value,
                details.remoteShare?.percent,
                details.employmentType,
                details.seniority,
                details.deadline,
                details.howApplied,
                details.payBand?.let(PayBandResult::from),
                details.offer?.let(OfferResult::from),
                Untrusted(LanguageAndToneResult.from(details.languageAndTone)),
                Untrusted(PostingFields(details.title, details.location)),
                Untrusted(notesOf(details)),
                ApplicationReadOnly.from(application),
            )
        }

        private fun notesOf(details: ApplicationDetails) =
            ApplicationNotes(
                details.portalNotes,
                (details.payBand?.source as? PaySource.Estimated)?.basis,
                details.offer?.let { OfferTexts(it.bonus, it.benefits, it.noticePeriod) },
            )
    }
}

/** The posting's own fields a tool can write. */
data class PostingFields(
    val title: String,
    val location: String?,
)

/** The texts of an application besides its posting. */
data class ApplicationNotes(
    val portalNotes: String?,
    val payEstimateBasis: String?,
    val offer: OfferTexts?,
)

/**
 * What no application tool changes (status, contacts, scores, flags, timestamps, where it was found, why it ended):
 * shown for reading, never sent back to `update_application`. Its third-party texts are [Untrusted].
 */
data class ApplicationReadOnly(
    val status: ApplicationStatus,
    val declineCategory: DeclineCategory?,
    val unread: Boolean,
    val contactIds: List<UUID>,
    val wantScore: BigDecimal?,
    val fitScore: BigDecimal?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val texts: Untrusted<ReadOnlyTexts>,
) {
    companion object {
        fun from(application: Application) =
            ApplicationReadOnly(
                application.status,
                application.declineReason?.category,
                application.unread,
                application.contacts.map { it.value }.sorted(),
                application.wantScore?.let(::points),
                application.fitScore?.let(::points),
                application.createdAt,
                application.updatedAt,
                Untrusted(ReadOnlyTexts(application.sources.map(SourceResult::from), application.declineReason?.text)),
            )

        private fun points(score: Score): BigDecimal = BigDecimal.valueOf(score.tenths.toLong(), 1)
    }
}

data class ReadOnlyTexts(
    val sources: List<SourceResult>,
    val declineReason: String?,
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
