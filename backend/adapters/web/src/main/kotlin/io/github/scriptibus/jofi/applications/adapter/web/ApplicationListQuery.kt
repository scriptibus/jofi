// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationSearchInput
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.SourceKind
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Copy of `ApplicationSortKey`: what the list is sorted by. */
enum class ApplicationListSort { UPDATED, CREATED, TITLE, COMPANY, STATUS, DEADLINE }

/** Copy of `SortDirection`. */
enum class ApplicationListDirection { ASCENDING, DESCENDING }

/**
 * The filter and sort parameters of `GET /api/applications`, bound by Spring from the query string
 * (one parameter object, so the controller keeps to five parameters; paging stays two plain
 * parameters). Every filter given must match; repeat a list parameter (`status=APPLIED&status=OFFER`)
 * to match any of its values. [search] matches titles fuzzily; [language] matches the application
 * language (or the posting's, when none is set) including its subtags (`de` finds `de-CH`);
 * [sourceKind] matches applications with at least one source of that kind; the `*From` bounds are
 * inclusive, the `*To` bounds exclusive; scores are 0 to 5 with one decimal and never match an
 * application without that score. Without [sort] the best title match comes first when searching,
 * otherwise the most recently updated; [direction] defaults to newest first for dates and A to Z
 * otherwise. Everything is optional.
 */
data class ApplicationListQuery(
    val search: String? = null,
    val companyId: UUID? = null,
    val contactId: UUID? = null,
    val status: List<PipelineStatus>? = null,
    val unread: Boolean? = null,
    val language: List<String>? = null,
    val sourceKind: List<PostingSourceKind>? = null,
    val createdFrom: Instant? = null,
    val createdTo: Instant? = null,
    val updatedFrom: Instant? = null,
    val updatedTo: Instant? = null,
    val wantMin: BigDecimal? = null,
    val wantMax: BigDecimal? = null,
    val fitMin: BigDecimal? = null,
    val fitMax: BigDecimal? = null,
    val sort: ApplicationListSort? = null,
    val direction: ApplicationListDirection? = null,
) {
    /** The search for these filters and the page [page] of [size] applications. */
    fun toInput(
        page: Int,
        size: Int,
    ): ApplicationSearchInput =
        ApplicationSearchInput(
            text = search,
            company = companyId?.let(::CompanyRef),
            contact = contactId?.let(::ContactRef),
            statuses = status.orEmpty().mapTo(mutableSetOf()) { it.mapByName<ApplicationStatus>() },
            unread = unread,
            languages = language.orEmpty(),
            sourceKinds = sourceKind.orEmpty().mapTo(mutableSetOf()) { it.mapByName<SourceKind>() },
            createdFrom = createdFrom,
            createdTo = createdTo,
            updatedFrom = updatedFrom,
            updatedTo = updatedTo,
            wantMin = wantMin,
            wantMax = wantMax,
            fitMin = fitMin,
            fitMax = fitMax,
            sort = sort?.mapByName(),
            direction = direction?.mapByName(),
            page = page,
            size = size,
        )

    /** Leaves out [search], which may quote a title. */
    override fun toString(): String = "ApplicationListQuery(companyId=$companyId, contactId=$contactId, status=$status)"
}
