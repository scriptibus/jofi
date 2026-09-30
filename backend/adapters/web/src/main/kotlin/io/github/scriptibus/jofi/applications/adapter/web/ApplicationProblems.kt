// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SavedViewField
import io.github.scriptibus.jofi.applications.domain.SearchField
import io.github.scriptibus.jofi.applications.domain.SearchViolation
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
    const val INVALID_TIMELINE_QUERY = "urn:jofi:problem:applications:invalid-timeline-query"
    const val NOT_FOUND = "urn:jofi:problem:applications:application-not-found"
    const val SOURCE_NOT_FOUND = "urn:jofi:problem:applications:source-not-found"
    const val SNAPSHOT_NOT_FOUND = "urn:jofi:problem:applications:snapshot-not-found"
    const val INTERVIEW_NOT_FOUND = "urn:jofi:problem:applications:interview-not-found"
    const val INVALID_VIEW = "urn:jofi:problem:applications:invalid-saved-view"
    const val SAVED_VIEW_NOT_FOUND = "urn:jofi:problem:applications:saved-view-not-found"
    const val IMPORT_NOT_FOUND = "urn:jofi:problem:applications:import-not-found"
    const val IMPORT_NOT_RETRYABLE = "urn:jofi:problem:applications:import-not-retryable"
    const val AI_NOT_CONFIGURED = "urn:jofi:problem:applications:ai-not-configured"
    const val VERSION_CONFLICT = "urn:jofi:problem:applications:version-conflict"
    const val INVALID_TRANSITION = "urn:jofi:problem:applications:invalid-transition"
    const val UNAVAILABLE = "urn:jofi:problem:applications:storage-unavailable"

    fun of(failure: ApplicationResult.Failure): ErrorResponseException =
        when (failure) {
            is ApplicationResult.Invalid, is ApplicationResult.InvalidView -> {
                invalid(failure)
            }

            ApplicationResult.NotFound,
            ApplicationResult.SourceNotFound,
            ApplicationResult.SnapshotNotFound,
            ApplicationResult.InterviewNotFound,
            ApplicationResult.SavedViewNotFound,
            ApplicationResult.ImportNotFound,
            -> {
                listed(HttpStatus.NOT_FOUND, NOT_FOUND_PROBLEMS, failure)
            }

            ApplicationResult.VersionConflict,
            ApplicationResult.ImportNotRetryable,
            ApplicationResult.AiNotConfigured,
            -> {
                listed(HttpStatus.CONFLICT, CONFLICT_PROBLEMS, failure)
            }

            is ApplicationResult.InvalidTransition -> {
                problem(HttpStatus.CONFLICT, INVALID_TRANSITION, transitionDetail(failure))
            }

            is ApplicationResult.Unconfirmed -> {
                Confirmations.problem(failure.outcome)
            }

            is ApplicationResult.StorageFailure -> {
                problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "Applications cannot be stored right now")
            }
        }

    /** The 400 for search parameters `ApplicationSearchInput.validate` refused, named as query parameters. */
    fun invalidSearch(violations: List<SearchViolation>): ErrorResponseException =
        ValidationProblem.of(
            INVALID_SEARCH,
            violations.map {
                FieldViolation(SEARCH_PARAMETERS.getValue(it.field), it.problem.name)
            },
        )

    private fun invalid(failure: ApplicationResult.Failure): ErrorResponseException =
        if (failure is ApplicationResult.InvalidView) {
            ValidationProblem.of(
                INVALID_VIEW,
                failure.violations.map { FieldViolation(viewFieldName(it.field), it.problem.name) },
            )
        } else {
            val violations = (failure as ApplicationResult.Invalid).violations
            ValidationProblem.of(INVALID, violations.map { FieldViolation(apiName(it.field), it.problem.name) })
        }

    /** A saved view's request field: `name`, or `filter.` and the list's query parameter (`filter.wantMax`). */
    fun viewFieldName(field: SavedViewField): String =
        when (field) {
            SavedViewField.Name -> "name"
            is SavedViewField.Filter -> "filter." + SEARCH_PARAMETERS.getValue(field.field)
        }

    /** The request field a violation belongs to, e.g. `payBand.max`, so clients can show it there. */
    fun apiName(field: ApplicationField): String = API_NAMES.getValue(field)

    private val NOT_FOUND_PROBLEMS: Map<ApplicationResult.Failure, Pair<String, String>> =
        mapOf(
            ApplicationResult.NotFound to (NOT_FOUND to "No application with this id"),
            ApplicationResult.SourceNotFound to (SOURCE_NOT_FOUND to "The application has no source with this id"),
            ApplicationResult.SnapshotNotFound to
                (SNAPSHOT_NOT_FOUND to "The application has no description with this id"),
            ApplicationResult.InterviewNotFound to
                (INTERVIEW_NOT_FOUND to "The application has no interview with this id"),
            ApplicationResult.SavedViewNotFound to (SAVED_VIEW_NOT_FOUND to "No saved view with this id"),
            ApplicationResult.ImportNotFound to (IMPORT_NOT_FOUND to "No posting import with this id"),
        )

    private val CONFLICT_PROBLEMS: Map<ApplicationResult.Failure, Pair<String, String>> =
        mapOf(
            ApplicationResult.VersionConflict to (VERSION_CONFLICT to "It changed meanwhile; reload it and retry"),
            ApplicationResult.ImportNotRetryable to (IMPORT_NOT_RETRYABLE to "Only a failed import can be retried"),
            ApplicationResult.AiNotConfigured to
                (
                    AI_NOT_CONFIGURED to
                        "No AI model reads postings yet; assign one to the extraction task in the AI setup"
                ),
        )

    private val SEARCH_PARAMETERS: Map<SearchField, String> =
        mapOf(
            SearchField.TEXT to "search",
            SearchField.LANGUAGES to "language",
            SearchField.CREATED_TO to "createdTo",
            SearchField.UPDATED_TO to "updatedTo",
            SearchField.WANT_MIN to "wantMin",
            SearchField.WANT_MAX to "wantMax",
            SearchField.FIT_MIN to "fitMin",
            SearchField.FIT_MAX to "fitMax",
            SearchField.PAGE to "page",
            SearchField.SIZE to "size",
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
            ApplicationField.INTERVIEW_START to "localStart",
            ApplicationField.TIME_ZONE to "timeZone",
            ApplicationField.PARTICIPANTS to "participantIds",
            ApplicationField.PREPARATION_NOTES to "preparationNotes",
            ApplicationField.INTERVIEW_NOTES to "notes",
            ApplicationField.GHOSTED_AFTER_WEEKS to "ghostedAfterWeeks",
            ApplicationField.FOLLOW_UP_AFTER_DAYS to "followUpAfterDays",
        )

    /** The problem [problems] lists for [failure], with [status]. */
    private fun listed(
        status: HttpStatus,
        problems: Map<ApplicationResult.Failure, Pair<String, String>>,
        failure: ApplicationResult.Failure,
    ): ErrorResponseException {
        val (type, detail) = problems.getValue(failure)
        return problem(status, type, detail)
    }

    private fun transitionDetail(failure: ApplicationResult.InvalidTransition): String =
        "An application cannot move from ${failure.from} to ${failure.to}"

    private fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ErrorResponseException {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply { this.type = URI.create(type) }
        return ErrorResponseException(status, body, null)
    }
}
