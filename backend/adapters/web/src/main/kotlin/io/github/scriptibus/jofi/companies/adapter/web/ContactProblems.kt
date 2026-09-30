// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.ContactField
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.companies.domain.ContactViolation
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.net.URI

/**
 * How contact failures answer over REST (ADR-0041): the status and a `urn:jofi:problem:companies:*`
 * type per case. No personal data in `detail`: contacts are third-party data (spec §13).
 */
object ContactProblems {
    const val INVALID = "urn:jofi:problem:companies:invalid-contact"
    const val INVALID_SEARCH = "urn:jofi:problem:companies:invalid-contact-search"
    const val NOT_FOUND = "urn:jofi:problem:companies:contact-not-found"
    const val VERSION_CONFLICT = "urn:jofi:problem:companies:contact-version-conflict"

    fun of(failure: ContactResult.Failure): ErrorResponseException =
        when (failure) {
            is ContactResult.Invalid -> {
                ValidationProblem.of(INVALID, failure.violations.map { FieldViolation(apiName(it), it.problem.name) })
            }

            ContactResult.NotFound -> {
                problem(HttpStatus.NOT_FOUND, NOT_FOUND, "No contact with this id")
            }

            ContactResult.VersionConflict -> {
                problem(HttpStatus.CONFLICT, VERSION_CONFLICT, "The contact changed meanwhile; reload it and retry")
            }

            is ContactResult.Unconfirmed -> {
                Confirmations.problem(failure.outcome)
            }

            is ContactResult.StorageFailure -> {
                problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    CompanyProblems.UNAVAILABLE,
                    "Contacts cannot be stored right now",
                )
            }
        }

    /** The 400 for search parameters [ContactSearch.of] refused. */
    fun invalidSearch(
        page: Int,
        size: Int,
    ): ErrorResponseException {
        val violations =
            listOfNotNull(
                FieldViolation("page", CompanyProblems.OUT_OF_RANGE).takeIf { page < 0 },
                FieldViolation("size", CompanyProblems.OUT_OF_RANGE).takeIf { size !in 1..ContactSearch.MAX_SIZE },
            )
        return ValidationProblem.of(INVALID_SEARCH, violations)
    }

    /** The request field a violation belongs to, e.g. `channels[2].value`, so clients can show it there. */
    fun apiName(violation: ContactViolation): String =
        when (violation.field) {
            ContactField.NAME -> "name"
            ContactField.ROLE -> "role"
            ContactField.COMPANY -> "companyId"
            ContactField.CHANNELS -> "channels"
            ContactField.CHANNEL_VALUE -> "channels[${violation.channel}].value"
            ContactField.CHANNEL_LABEL -> "channels[${violation.channel}].label"
            ContactField.RELATIONSHIP_NOTES -> "relationshipNotes"
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
