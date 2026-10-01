// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.CompanyView
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import java.time.Instant
import java.util.UUID

/**
 * The company tools' results. A company's name, website, industry and locations can come from imported
 * postings and web pages, so they are [Untrusted]; the user's own notes and preference reason stay plain.
 */
data class CompanyFacts(
    val name: String,
    val website: String?,
    val industry: String?,
    val size: CompanySize?,
    val locations: List<String>,
    val careersPage: String?,
)

data class CompanySummary(
    val id: UUID,
    val applicationCount: Int,
    val preference: PreferenceKind,
    val company: Untrusted<CompanyFacts>,
) {
    companion object {
        fun from(view: CompanyView) =
            CompanySummary(
                view.company.id.value,
                view.applicationCount,
                view.company.preference.kind,
                Untrusted(factsOf(view)),
            )
    }
}

data class CompanySearchResult(
    val total: Long,
    val page: Int,
    val size: Int,
    val companies: List<CompanySummary>,
) {
    companion object {
        fun from(
            page: CompanyPage<CompanyView>,
            number: Int,
            size: Int,
        ) = CompanySearchResult(page.total, number, size, page.items.map(CompanySummary::from))
    }
}

/** One company in full; [version] is what `update_company` needs to be based on. */
data class CompanyDetailResult(
    val id: UUID,
    val version: Long,
    val applicationCount: Int,
    val preference: PreferenceKind,
    val preferenceReason: String?,
    val researchNotes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val company: Untrusted<CompanyFacts>,
) {
    companion object {
        fun from(view: CompanyView): CompanyDetailResult {
            val company = view.company
            return CompanyDetailResult(
                company.id.value,
                company.version,
                view.applicationCount,
                company.preference.kind,
                company.preference.reason,
                company.details.researchNotes,
                company.createdAt,
                company.updatedAt,
                Untrusted(factsOf(view)),
            )
        }
    }
}

private fun factsOf(view: CompanyView): CompanyFacts {
    val details = view.company.details
    return CompanyFacts(
        details.name,
        details.website?.value,
        details.industry,
        details.size,
        details.locations,
        details.careersPage?.value,
    )
}

/** The tool errors of the companies: stable codes, no stored content. */
internal object CompanyToolErrors {
    fun failure(failure: CompanyResult.Failure): ToolAnswer.Error =
        when (failure) {
            is CompanyResult.Invalid -> {
                ToolAnswer.Error(
                    "invalid-arguments",
                    "The company arguments are invalid.",
                    failure.violations.map {
                        ArgumentProblem(
                            ToolProblems.argumentName(it.field.name),
                            ToolProblems.problemCode(it.problem.name),
                        )
                    },
                )
            }

            CompanyResult.NotFound -> {
                ToolAnswer.Error("not-found", "No company has this id.")
            }

            CompanyResult.VersionConflict -> {
                ToolProblems.versionConflict()
            }

            is CompanyResult.StorageFailure -> {
                ToolAnswer.Error("unavailable", "Companies cannot be used now.")
            }

            else -> {
                ToolAnswer.Error("failed", "The company request could not be completed.")
            }
        }
}
