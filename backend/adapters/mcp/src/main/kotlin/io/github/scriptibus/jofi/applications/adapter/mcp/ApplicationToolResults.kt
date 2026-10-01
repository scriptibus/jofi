// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.HowApplied
import io.github.scriptibus.jofi.applications.domain.PayBand
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySource
import io.github.scriptibus.jofi.applications.domain.PaySourceKind
import io.github.scriptibus.jofi.applications.domain.Score
import io.github.scriptibus.jofi.applications.domain.SearchViolation
import io.github.scriptibus.jofi.applications.domain.Seniority
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
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

/** One application in full, except its language and tone and the offer, which no tool needs yet. */
data class ApplicationDetailResult(
    val id: UUID,
    val version: Long,
    val companyId: UUID,
    val contactIds: List<UUID>,
    val status: ApplicationStatus,
    val declineCategory: DeclineCategory?,
    val declineReason: String?,
    val unread: Boolean,
    val remoteSharePercent: Int?,
    val employmentType: EmploymentType?,
    val seniority: Seniority?,
    val deadline: LocalDate?,
    val howApplied: HowApplied?,
    val portalNotes: String?,
    val payBand: PayBandResult?,
    val wantScore: BigDecimal?,
    val fitScore: BigDecimal?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val posting: Untrusted<PostingDetails>,
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
                application.declineReason?.text,
                application.unread,
                details.remoteShare?.percent,
                details.employmentType,
                details.seniority,
                details.deadline,
                details.howApplied,
                details.portalNotes,
                details.payBand?.let(PayBandResult::from),
                application.wantScore?.let(::points),
                application.fitScore?.let(::points),
                application.createdAt,
                application.updatedAt,
                Untrusted(PostingDetails(details.title, details.location, application.sources.map(SourceResult::from))),
            )
        }

        private fun points(score: Score): BigDecimal = BigDecimal.valueOf(score.tenths.toLong(), 1)
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
            )
    }
}

/** The tool errors of the applications context: stable codes, no stored content. */
internal object ApplicationToolErrors {
    fun invalidSearch(violations: List<SearchViolation>) =
        ToolAnswer.Error(
            "invalid-arguments",
            "The search arguments are invalid.",
            violations.map {
                ArgumentProblem(ToolProblems.argumentName(it.field.name), ToolProblems.problemCode(it.problem.name))
            },
        )

    fun failure(failure: ApplicationResult.Failure): ToolAnswer.Error =
        when (failure) {
            ApplicationResult.NotFound -> ToolAnswer.Error("not-found", "No application has this id.")
            is ApplicationResult.StorageFailure -> ToolAnswer.Error("unavailable", "Applications cannot be read now.")
            else -> ToolAnswer.Error("failed", "The applications could not be read.")
        }
}
