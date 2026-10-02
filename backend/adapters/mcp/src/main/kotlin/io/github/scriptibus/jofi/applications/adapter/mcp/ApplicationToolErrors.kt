// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.SearchViolation
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems

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

    /** Every outcome of the application use cases; a new one breaks this `when` instead of becoming a vague code. */
    fun failure(
        failure: ApplicationResult.Failure,
        unavailable: String = "Applications cannot be used now.",
    ): ToolAnswer.Error =
        when (failure) {
            is ApplicationResult.Invalid -> invalid(failure)

            ApplicationResult.VersionConflict -> ToolProblems.versionConflict()

            is ApplicationResult.InvalidTransition -> invalidTransition(failure)

            is ApplicationResult.StorageFailure -> ToolAnswer.Error("unavailable", unavailable)

            // The outcomes with a fixed answer, in FIXED (ApplicationToolErrorsTest checks that each has one).
            ApplicationResult.NotFound,
            ApplicationResult.InterviewNotFound,
            ApplicationResult.ImportNotFound,
            ApplicationResult.AiNotConfigured,
            ApplicationResult.ImportInProgress,
            ApplicationResult.ImportBusy,
            -> FIXED.getValue(failure)

            // Sources, snapshots, saved views and retries have no tool (yet): none of these can happen.
            ApplicationResult.SourceNotFound,
            ApplicationResult.SnapshotNotFound,
            ApplicationResult.SavedViewNotFound,
            is ApplicationResult.InvalidView,
            ApplicationResult.ImportNotRetryable,
            is ApplicationResult.Unconfirmed,
            -> ToolAnswer.Error("failed", "The request could not be completed.")
        }

    private val FIXED: Map<ApplicationResult.Failure, ToolAnswer.Error> =
        mapOf(
            ApplicationResult.NotFound to ToolAnswer.Error("not-found", "No application has this id."),
            ApplicationResult.InterviewNotFound to ToolAnswer.Error("not-found", "No such interview."),
            ApplicationResult.ImportNotFound to ToolAnswer.Error("not-found", "No import has this id."),
            ApplicationResult.AiNotConfigured to
                ToolAnswer.Error(
                    "ai-not-configured",
                    "No AI model is set up for reading postings. The user sets one up in the settings first.",
                ),
            ApplicationResult.ImportInProgress to
                ToolAnswer.Error(
                    "import-in-progress",
                    "Another request is importing this very link. Try again shortly: by then its import is known.",
                ),
            ApplicationResult.ImportBusy to
                ToolAnswer.Error(
                    "import-busy",
                    "The server is fetching as many pages as it allows. Try again shortly; nothing was fetched.",
                ),
        )

    private fun invalid(failure: ApplicationResult.Invalid) =
        ToolAnswer.Error("invalid-arguments", messageOf(failure), failure.violations.map(::problemOf))

    /** A link that cannot be imported says what to do instead: paste the text. */
    private fun messageOf(failure: ApplicationResult.Invalid): String {
        val problems = failure.violations.filter { it.field == ApplicationField.SOURCE_URL }.map { it.problem }
        return when {
            ApplicationProblem.NOT_ALLOWED in problems -> NOT_ALLOWED_LINK
            ApplicationProblem.UNREACHABLE in problems -> UNREACHABLE_LINK
            else -> "The arguments are invalid."
        }
    }

    private fun invalidTransition(failure: ApplicationResult.InvalidTransition) =
        ToolAnswer.Error(
            "invalid-transition",
            "An application cannot move from ${failure.from} to ${failure.to}. Read it again for its status.",
        )

    /** The answer of a delete once the gate is passed: success, or the failure. */
    fun deleted(result: ApplicationResult<Unit>): ToolAnswer =
        when (result) {
            is ApplicationResult.Success -> ToolAnswer.Result(Unit)
            is ApplicationResult.Failure -> failure(result, "The delete cannot run now.")
        }

    private fun problemOf(violation: ApplicationViolation) =
        ArgumentProblem(argumentOf(violation.field), ToolProblems.problemCode(violation.problem.name))

    /** The argument of the application and interview tools a field belongs to. */
    fun argumentOf(field: ApplicationField): String = ARGUMENTS.getValue(field)

    private const val NOT_ALLOWED_LINK =
        "Jofi never fetches LinkedIn, StepStone or Indeed (their terms forbid it). Ask the user to paste the " +
            "posting's text and use start_text_import instead."
    private const val UNREACHABLE_LINK =
        "The page could not be fetched (blocked address, unreachable, too large or not readable). Ask the user to " +
            "paste the posting's text and use start_text_import instead."

    // A map, not a `when`, to keep the function short; ApplicationToolErrorsTest checks that every field is in it.
    private val ARGUMENTS =
        mapOf(
            ApplicationField.TITLE to "posting.title",
            ApplicationField.COMPANY to "companyId",
            ApplicationField.LOCATION to "posting.location",
            ApplicationField.REMOTE_SHARE to "remoteSharePercent",
            ApplicationField.PORTAL_NOTES to "notes.portalNotes",
            ApplicationField.PAY_MIN to "payBand.min",
            ApplicationField.PAY_MAX to "payBand.max",
            ApplicationField.PAY_CURRENCY to "payBand.currency",
            ApplicationField.PAY_ESTIMATE_BASIS to "notes.payEstimateBasis",
            ApplicationField.PAY_ESTIMATE_CONFIDENCE to "payBand.estimateConfidence",
            ApplicationField.POSTING_LANGUAGE to "languageAndTone.postingLanguage",
            ApplicationField.APPLICATION_LANGUAGE to "languageAndTone.applicationLanguage",
            ApplicationField.OFFER_SALARY to "offer.salary.amount",
            ApplicationField.OFFER_SALARY_CURRENCY to "offer.salary.currency",
            ApplicationField.OFFER_BONUS to "notes.offer.bonus",
            ApplicationField.OFFER_BENEFITS to "notes.offer.benefits",
            ApplicationField.OFFER_REMOTE_SHARE to "offer.remoteSharePercent",
            ApplicationField.OFFER_VACATION_DAYS to "offer.vacationDays",
            ApplicationField.OFFER_NOTICE_PERIOD to "notes.offer.noticePeriod",
            ApplicationField.CONTACTS to "contactIds",
            ApplicationField.STATUS_REASON to "reason",
            ApplicationField.DECLINE_CATEGORY to "declineCategory",
            ApplicationField.SOURCES to "sources",
            ApplicationField.SOURCE_URL to "url",
            ApplicationField.DISCOVERED_AT to "discoveredAt",
            ApplicationField.DESCRIPTION to "text",
            ApplicationField.INTERVIEW_START to "localStart",
            ApplicationField.TIME_ZONE to "timeZone",
            ApplicationField.PARTICIPANTS to "participantIds",
            ApplicationField.PREPARATION_NOTES to "interview.preparationNotes",
            ApplicationField.INTERVIEW_NOTES to "interview.notes",
            ApplicationField.GHOSTED_AFTER_WEEKS to "ghostedAfterWeeks",
            ApplicationField.FOLLOW_UP_AFTER_DAYS to "followUpAfterDays",
        )
}
