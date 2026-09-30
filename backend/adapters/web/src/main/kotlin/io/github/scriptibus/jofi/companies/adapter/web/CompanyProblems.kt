// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.CompanyField
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.net.URI

/**
 * How company failures answer over REST (ADR-0041): the status and a `urn:jofi:problem:companies:*`
 * type per case. The controller throws what [of] returns; no internals or personal data in `detail`.
 */
object CompanyProblems {
    const val INVALID = "urn:jofi:problem:companies:invalid-company"
    const val INVALID_SEARCH = "urn:jofi:problem:companies:invalid-search"
    const val NOT_FOUND = "urn:jofi:problem:companies:company-not-found"
    const val VERSION_CONFLICT = "urn:jofi:problem:companies:version-conflict"
    const val HAS_APPLICATIONS = "urn:jofi:problem:companies:has-applications"
    const val UNAVAILABLE = "urn:jofi:problem:companies:storage-unavailable"

    /** Problem for a violated range of the search parameters (see [CompanySearch.of]). */
    const val OUT_OF_RANGE = "OUT_OF_RANGE"

    fun of(failure: CompanyResult.Failure): ErrorResponseException =
        when (failure) {
            is CompanyResult.Invalid -> {
                ValidationProblem.of(
                    INVALID,
                    failure.violations.map { FieldViolation(apiName(it.field), it.problem.name) },
                )
            }

            CompanyResult.NotFound -> {
                problem(HttpStatus.NOT_FOUND, NOT_FOUND, "No company with this id")
            }

            CompanyResult.VersionConflict -> {
                problem(HttpStatus.CONFLICT, VERSION_CONFLICT, "The company changed meanwhile; reload it and retry")
            }

            CompanyResult.HasApplications -> {
                problem(HttpStatus.CONFLICT, HAS_APPLICATIONS, "The company still has applications")
            }

            is CompanyResult.Unconfirmed -> {
                Confirmations.problem(failure.outcome)
            }

            is CompanyResult.StorageFailure -> {
                problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "Companies cannot be stored right now")
            }
        }

    /** The 400 for search parameters [CompanySearch.of] refused. */
    fun invalidSearch(
        page: Int,
        size: Int,
    ): ErrorResponseException {
        val violations =
            listOfNotNull(
                FieldViolation("page", OUT_OF_RANGE).takeIf { page < 0 },
                FieldViolation("size", OUT_OF_RANGE).takeIf { size !in 1..CompanySearch.MAX_SIZE },
            )
        return ValidationProblem.of(INVALID_SEARCH, violations)
    }

    /** The request field a domain field arrives in, so clients can show the problem next to it. */
    fun apiName(field: CompanyField): String =
        when (field) {
            CompanyField.NAME -> "name"
            CompanyField.WEBSITE -> "website"
            CompanyField.INDUSTRY -> "industry"
            CompanyField.LOCATIONS -> "locations"
            CompanyField.CAREERS_PAGE -> "careersPage"
            CompanyField.RESEARCH_NOTES -> "researchNotes"
            CompanyField.PREFERENCE_REASON -> "reason"
        }

    private fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ErrorResponseException {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply { this.type = URI.create(type) }
        return ErrorResponseException(status, body, null)
    }
}
