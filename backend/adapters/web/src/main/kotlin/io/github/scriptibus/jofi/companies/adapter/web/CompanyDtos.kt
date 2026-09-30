// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.PreferenceInput
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import java.time.Instant
import java.util.UUID

/** Head-count band of a company (the API's copy of the domain's `CompanySize`). */
enum class CompanySizeBand {
    MICRO,
    SMALL,
    MEDIUM,
    LARGE,
    ENTERPRISE,
    ;

    fun toDomain(): CompanySize = CompanySize.valueOf(name)

    companion object {
        fun from(size: CompanySize): CompanySizeBand = valueOf(size.name)
    }
}

/** Whether a company is a favourite, blacklisted or neither (the API's copy of `PreferenceKind`). */
enum class CompanyPreferenceKind {
    NONE,
    FAVOURITE,
    BLACKLISTED,
    ;

    fun toDomain(): PreferenceKind = PreferenceKind.valueOf(name)

    companion object {
        fun from(kind: PreferenceKind): CompanyPreferenceKind = valueOf(kind.name)
    }
}

/**
 * What the user edits about a company. Text is trimmed, blank optional fields count as absent, and
 * blank or duplicate locations are dropped; a violation answers 400.
 */
data class CompanyDetailsRequest(
    val name: String,
    val website: String? = null,
    val industry: String? = null,
    val size: CompanySizeBand? = null,
    val locations: List<String>? = null,
    /** Careers page or ATS board URL. */
    val careersPage: String? = null,
    /** Markdown. */
    val researchNotes: String? = null,
) {
    fun toInput(): CompanyInput =
        CompanyInput(name, website, industry, size?.toDomain(), locations.orEmpty(), careersPage, researchNotes)
}

/** Body of `PUT /api/companies/{id}`; [basedOnVersion] is the `version` the client last read. */
data class UpdateCompanyRequest(
    val details: CompanyDetailsRequest,
    val basedOnVersion: Long,
)

/** Body of `PUT /api/companies/{id}/preference`; [reason] is ignored for `NONE`. */
data class CompanyPreferenceRequest(
    val preference: CompanyPreferenceKind,
    val reason: String? = null,
    val basedOnVersion: Long,
) {
    fun toInput(): PreferenceInput = PreferenceInput(preference.toDomain(), reason)
}

/** The AI-generated company profile (Markdown). */
data class CompanyProfileResponse(
    val markdown: String,
    val generatedAt: Instant,
)

/** One company. [version] goes back as `basedOnVersion` with the next change. */
data class CompanyResponse(
    val id: UUID,
    val name: String,
    val website: String?,
    val industry: String?,
    val size: CompanySizeBand?,
    val locations: List<String>,
    val careersPage: String?,
    val researchNotes: String?,
    val profile: CompanyProfileResponse?,
    val preference: CompanyPreferenceKind,
    val preferenceReason: String?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(company: Company): CompanyResponse =
            with(company.details) {
                CompanyResponse(
                    id = company.id.value,
                    name = name,
                    website = website?.toString(),
                    industry = industry,
                    size = size?.let(CompanySizeBand::from),
                    locations = locations,
                    careersPage = careersPage?.toString(),
                    researchNotes = researchNotes,
                    profile = company.profile?.let { CompanyProfileResponse(it.markdown, it.generatedAt) },
                    preference = CompanyPreferenceKind.from(company.preference.kind),
                    preferenceReason = company.preference.reason,
                    version = company.version,
                    createdAt = company.createdAt,
                    updatedAt = company.updatedAt,
                )
            }
    }
}

/** JSON body of `GET /api/companies`: one page of companies. */
data class CompanyPageResponse(
    val companies: List<CompanyResponse>,
    val page: Int,
    val size: Int,
    /** All companies matching the search, across pages. */
    val total: Long,
) {
    companion object {
        fun from(
            page: CompanyPage,
            number: Int,
            size: Int,
        ): CompanyPageResponse =
            CompanyPageResponse(page.companies.map(CompanyResponse::from), number, size, page.total)
    }
}
