// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.text.TextProblem
import io.github.scriptibus.jofi.shared.domain.text.textProblem

/** A problem with one field of an [ApplicationInput], named so that clients can show it next to the field. */
data class ApplicationViolation(
    val field: ApplicationField,
    val problem: ApplicationProblem,
)

enum class ApplicationField {
    TITLE,
    COMPANY,
    LOCATION,
    REMOTE_SHARE,
    PORTAL_NOTES,
    PAY_MIN,
    PAY_MAX,
    PAY_CURRENCY,
    PAY_ESTIMATE_BASIS,
    PAY_ESTIMATE_CONFIDENCE,
    POSTING_LANGUAGE,
    APPLICATION_LANGUAGE,
    OFFER_SALARY,
    OFFER_SALARY_CURRENCY,
    OFFER_BONUS,
    OFFER_BENEFITS,
    OFFER_REMOTE_SHARE,
    OFFER_VACATION_DAYS,
    OFFER_NOTICE_PERIOD,

    /** The linked contacts: too many, or one that does not exist. */
    CONTACTS,

    /** Why the status changes ([StatusChangeInput.reason]). */
    STATUS_REASON,

    /** The decline category of a status change: required for `DECLINED` and `REJECTED`, absent otherwise. */
    DECLINE_CATEGORY,

    /** The application's sources: too many. */
    SOURCES,

    /** A source's original link. */
    SOURCE_URL,

    /** When a source was found: not in the future. */
    DISCOVERED_AT,

    /** A job description's text (a source's first one, or a new version). */
    DESCRIPTION,

    /** When an interview starts: within `InterviewTime.EARLIEST` and `InterviewTime.LATEST`. */
    INTERVIEW_START,

    /** The time zone an interview was planned in. */
    TIME_ZONE,

    /** An interview's participants: too many, or one that does not exist. */
    PARTICIPANTS,

    /** The notes to prepare an interview. */
    PREPARATION_NOTES,

    /** The user's notes after an interview. */
    INTERVIEW_NOTES,

    /** After how many weeks without news Ghosted is suggested ([ApplicationSettings.GHOSTED_WEEKS]). */
    GHOSTED_AFTER_WEEKS,

    /** After how many days without a response a follow-up is suggested ([ApplicationSettings.FOLLOW_UP_DAYS]). */
    FOLLOW_UP_AFTER_DAYS,
}

enum class ApplicationProblem {
    /** The field is required but empty. */
    REQUIRED,

    /** The text is longer than allowed. */
    TOO_LONG,

    /** The list has more entries than allowed. */
    TOO_MANY,

    /** The text contains U+0000, which the database cannot store. */
    INVALID_CHARACTER,

    /** The number is outside its range (e.g. a negative amount or a remote share above 100). */
    OUT_OF_RANGE,

    /** The amount has more than two decimals. */
    TOO_PRECISE,

    /** Not an ISO 4217 code of three letters. */
    INVALID_CURRENCY,

    /** Not a BCP 47 language tag such as `de` or `en-GB`. */
    INVALID_LANGUAGE,

    /** The maximum of a pay band is below its minimum. */
    MIN_ABOVE_MAX,

    /** The referenced entity (the company, a contact) does not exist. */
    NOT_FOUND,

    /** The field does not apply here, e.g. a decline category for a status other than Declined or Rejected. */
    NOT_APPLICABLE,

    /** Not an absolute http(s) URL with a host and without user info. */
    INVALID_URL,

    /** Not a time zone Java knows: an IANA id such as `Europe/Berlin`, or an offset such as `+02:00`. */
    INVALID_TIME_ZONE,

    /** Another saved view has this name already (names are unique ignoring case). */
    TAKEN,
}

/** Shared by the invariants and [ApplicationInput.validate]. */
internal object ApplicationRules {
    fun textProblemOf(
        text: String,
        maxLength: Int,
    ): ApplicationProblem? =
        when (textProblem(text, maxLength)) {
            TextProblem.BLANK_OR_UNTRIMMED -> ApplicationProblem.REQUIRED
            TextProblem.UNSTORABLE_CHARACTER -> ApplicationProblem.INVALID_CHARACTER
            TextProblem.TOO_LONG -> ApplicationProblem.TOO_LONG
            null -> null
        }
}

/**
 * Outcome of an application use case (#82, #83, #84, #85, #86, #90, #91, #92, #96, #99). Callers map every case:
 * the REST controller to a status and problem type, an MCP tool to a tool error.
 */
sealed interface ApplicationResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : ApplicationResult<T>

    /** Every outcome but [Success]: nothing was changed. */
    sealed interface Failure : ApplicationResult<Nothing>

    /**
     * The input breaks the rules of [ApplicationInput], or names a company or contact that does not
     * exist ([ApplicationField.COMPANY] or [ApplicationField.CONTACTS], [ApplicationProblem.NOT_FOUND]).
     */
    data class Invalid(
        val violations: List<ApplicationViolation>,
    ) : Failure

    data object NotFound : Failure

    /** The application has no source with the requested id. */
    data object SourceNotFound : Failure

    /** The application has no description snapshot with the requested id. */
    data object SnapshotNotFound : Failure

    /** The application has no interview with the requested id. */
    data object InterviewNotFound : Failure

    /** No saved view with the requested id. */
    data object SavedViewNotFound : Failure

    /** No posting import with the requested id. */
    data object ImportNotFound : Failure

    /** Only a failed or stalled posting import can be retried; this one is done, or pending and not stalled. */
    data object ImportNotRetryable : Failure

    /** No AI model is assigned to the task the operation needs; the user sets one up first (spec §3.2). */
    data object AiNotConfigured : Failure

    /**
     * A saved view's name or filter breaks the rules of [SavedViewInput] (the list's own rules for the filter), or
     * another view has the name already ([SavedViewField.Name], [ApplicationProblem.TAKEN]).
     */
    data class InvalidView(
        val violations: List<SavedViewViolation>,
    ) : Failure

    /**
     * The change was based on an older version of the application (or interview, saved view or settings); nothing
     * was changed.
     */
    data object VersionConflict : Failure

    /** The status matrix (ADR-0044) has no move [from] the application's status [to] the requested one. */
    data class InvalidTransition(
        val from: ApplicationStatus,
        val to: ApplicationStatus,
    ) : Failure

    /** The delete needs (another) confirmation step (ADR-0039); nothing was deleted. */
    data class Unconfirmed(
        val outcome: ConfirmationResult.Unconfirmed,
    ) : Failure

    /** The store could not complete [operation]; nothing was changed. */
    data class StorageFailure(
        val operation: String,
    ) : Failure
}

/** Outcome of an application repository call; storage failures are values, not exceptions. */
sealed interface ApplicationStoreResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : ApplicationStoreResult<T>

    /**
     * No application with the requested id, or the source, snapshot or interview asked for does not exist (for
     * that application), or no saved view with that id; an insert whose application or source is gone (by its
     * foreign key's name) too.
     */
    data object NotFound : ApplicationStoreResult<Nothing>

    /**
     * The stored application (or interview, saved view or settings) has a newer version than the change was based
     * on; reload and retry.
     */
    data object VersionConflict : ApplicationStoreResult<Nothing>

    /** The application's company does not exist (any more): `application_company_fk` rejected it. */
    data object CompanyNotFound : ApplicationStoreResult<Nothing>

    /**
     * A linked contact or an interview participant does not exist (any more): `application_contact_contact_fk` or
     * `interview_participant_contact_fk` rejected it.
     */
    data object ContactNotFound : ApplicationStoreResult<Nothing>

    /** The application has [Application.MAX_SOURCES] sources already (counted under a lock); nothing was added. */
    data object SourceLimitReached : ApplicationStoreResult<Nothing>

    /** Another saved view has exactly this name: `saved_view_name_unique` rejected it; nothing was stored. */
    data object ViewNameTaken : ApplicationStoreResult<Nothing>

    /** The confirmation proof does not cover deleting this application; nothing was deleted. */
    data object NotConfirmed : ApplicationStoreResult<Nothing>

    /** The store could not complete [operation]. Carries no row data, so it is safe to log. */
    data class StorageFailure(
        val operation: String,
    ) : ApplicationStoreResult<Nothing>
}

/** One page of [items] and the number of all items matching the search. */
data class ApplicationPage<out T>(
    val items: List<T>,
    val total: Long,
) {
    init {
        require(total >= items.size) { "The total cannot be smaller than the page" }
    }
}
