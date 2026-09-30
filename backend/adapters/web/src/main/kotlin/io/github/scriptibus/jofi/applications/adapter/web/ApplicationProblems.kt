// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.net.URI

/**
 * How application failures answer over REST (ADR-0041): the status and a
 * `urn:jofi:problem:applications:*` type per case. No notes or titles in `detail`.
 */
object ApplicationProblems {
    const val INVALID = "urn:jofi:problem:applications:invalid-application"
    const val INVALID_SEARCH = "urn:jofi:problem:applications:invalid-search"
    const val NOT_FOUND = "urn:jofi:problem:applications:application-not-found"
    const val SOURCE_NOT_FOUND = "urn:jofi:problem:applications:source-not-found"
    const val SNAPSHOT_NOT_FOUND = "urn:jofi:problem:applications:snapshot-not-found"
    const val VERSION_CONFLICT = "urn:jofi:problem:applications:version-conflict"
    const val INVALID_TRANSITION = "urn:jofi:problem:applications:invalid-transition"
    const val UNAVAILABLE = "urn:jofi:problem:applications:storage-unavailable"

    /** The problem of a search parameter out of range. */
    const val OUT_OF_RANGE = "OUT_OF_RANGE"

    fun of(failure: ApplicationResult.Failure): ErrorResponseException =
        when (failure) {
            is ApplicationResult.Invalid -> {
                ValidationProblem.of(
                    INVALID,
                    failure.violations.map { FieldViolation(apiName(it.field), it.problem.name) },
                )
            }

            ApplicationResult.NotFound, ApplicationResult.SourceNotFound, ApplicationResult.SnapshotNotFound -> {
                val (type, detail) = NOT_FOUND_PROBLEMS.getValue(failure)
                problem(HttpStatus.NOT_FOUND, type, detail)
            }

            ApplicationResult.VersionConflict -> {
                problem(HttpStatus.CONFLICT, VERSION_CONFLICT, "The application changed meanwhile; reload it and retry")
            }

            is ApplicationResult.InvalidTransition -> {
                problem(
                    HttpStatus.CONFLICT,
                    INVALID_TRANSITION,
                    "An application cannot move from ${failure.from} to ${failure.to}",
                )
            }

            is ApplicationResult.Unconfirmed -> {
                Confirmations.problem(failure.outcome)
            }

            is ApplicationResult.StorageFailure -> {
                problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "Applications cannot be stored right now")
            }
        }

    /** The 400 for search parameters [ApplicationSearch.of] refused. */
    fun invalidSearch(
        page: Int,
        size: Int,
    ): ErrorResponseException {
        val violations =
            listOfNotNull(
                FieldViolation("page", OUT_OF_RANGE).takeIf { page < 0 },
                FieldViolation("size", OUT_OF_RANGE).takeIf { size !in 1..ApplicationSearch.MAX_SIZE },
            )
        return ValidationProblem.of(INVALID_SEARCH, violations)
    }

    /** The request field a violation belongs to, e.g. `payBand.max`, so clients can show it there. */
    fun apiName(field: ApplicationField): String = API_NAMES.getValue(field)

    private val NOT_FOUND_PROBLEMS: Map<ApplicationResult.Failure, Pair<String, String>> =
        mapOf(
            ApplicationResult.NotFound to (NOT_FOUND to "No application with this id"),
            ApplicationResult.SourceNotFound to (SOURCE_NOT_FOUND to "The application has no source with this id"),
            ApplicationResult.SnapshotNotFound to
                (SNAPSHOT_NOT_FOUND to "The application has no description with this id"),
        )

    private val API_NAMES: Map<ApplicationField, String> =
        mapOf(
            ApplicationField.TITLE to "title",
            ApplicationField.COMPANY to "companyId",
            ApplicationField.LOCATION to "location",
            ApplicationField.REMOTE_SHARE to "remoteShare",
            ApplicationField.PORTAL_NOTES to "portalNotes",
            ApplicationField.PAY_MIN to "payBand.min",
            ApplicationField.PAY_MAX to "payBand.max",
            ApplicationField.PAY_CURRENCY to "payBand.currency",
            ApplicationField.PAY_ESTIMATE_BASIS to "payBand.estimateBasis",
            ApplicationField.PAY_ESTIMATE_CONFIDENCE to "payBand.estimateConfidence",
            ApplicationField.POSTING_LANGUAGE to "languageAndTone.postingLanguage",
            ApplicationField.APPLICATION_LANGUAGE to "languageAndTone.applicationLanguage",
            ApplicationField.OFFER_SALARY to "offer.salary.amount",
            ApplicationField.OFFER_SALARY_CURRENCY to "offer.salary.currency",
            ApplicationField.OFFER_BONUS to "offer.bonus",
            ApplicationField.OFFER_BENEFITS to "offer.benefits",
            ApplicationField.OFFER_REMOTE_SHARE to "offer.remoteShare",
            ApplicationField.OFFER_VACATION_DAYS to "offer.vacationDays",
            ApplicationField.OFFER_NOTICE_PERIOD to "offer.noticePeriod",
            ApplicationField.CONTACTS to "contactIds",
            ApplicationField.STATUS_REASON to "reason",
            ApplicationField.DECLINE_CATEGORY to "declineCategory",
            ApplicationField.SOURCES to "sources",
            ApplicationField.SOURCE_URL to "originalUrl",
            ApplicationField.DISCOVERED_AT to "discoveredAt",
            ApplicationField.DESCRIPTION to "description",
        )

    private fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ErrorResponseException {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply { this.type = URI.create(type) }
        return ErrorResponseException(status, body, null)
    }
}
