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
    DECLINE_REASON_TEXT,
    OFFER_SALARY,
    OFFER_SALARY_CURRENCY,
    OFFER_BONUS,
    OFFER_BENEFITS,
    OFFER_REMOTE_SHARE,
    OFFER_VACATION_DAYS,
    OFFER_NOTICE_PERIOD,

    /** The linked contacts: too many, or one that does not exist. */
    CONTACTS,
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
 * Outcome of an application use case (#82, #83, #90). Callers map every case: the REST controller to
 * a status and problem type, an MCP tool to a tool error.
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

    /** The change was based on an older version of the application; nothing was changed. */
    data object VersionConflict : Failure

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

    /** No application with the requested id. */
    data object NotFound : ApplicationStoreResult<Nothing>

    /** The stored application has a newer version than the change was based on; reload and retry. */
    data object VersionConflict : ApplicationStoreResult<Nothing>

    /** The application's company does not exist (any more): `application_company_fk` rejected it. */
    data object CompanyNotFound : ApplicationStoreResult<Nothing>

    /** A linked contact does not exist (any more): `application_contact_contact_fk` rejected it. */
    data object ContactNotFound : ApplicationStoreResult<Nothing>

    /** The confirmation proof does not cover deleting this application; nothing was deleted. */
    data object NotConfirmed : ApplicationStoreResult<Nothing>

    /** The store could not complete [operation]. Carries no row data, so it is safe to log. */
    data class StorageFailure(
        val operation: String,
    ) : ApplicationStoreResult<Nothing>
}

/**
 * A page of the application list: [text] matches titles fuzzily (pg_trgm similarity, best match
 * first, otherwise newest first), [company] and [contact] keep the applications of one company or with
 * one linked contact. [page] counts from 0. The list issue (#83) adds the unread, status, score, source,
 * language and date filters and the sort order.
 */
data class ApplicationSearch(
    val text: String? = null,
    val company: CompanyRef? = null,
    val contact: ContactRef? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        require(text == null || text.isNotBlank()) { "A search text, when given, must not be blank" }
        require(page >= 0) { "A page number must not be negative" }
        require(size in 1..MAX_SIZE) { "A page holds 1 to $MAX_SIZE applications" }
    }

    override fun toString(): String = "ApplicationSearch(company=$company, contact=$contact, page=$page, size=$size)"

    companion object {
        const val DEFAULT_SIZE = 50
        const val MAX_SIZE = 200

        /**
         * The search for raw query parameters, or `null` if [page] or [size] is out of range (the caller
         * answers 400). Blank [text] searches every application.
         */
        fun of(
            text: String?,
            filters: ApplicationSearch = ApplicationSearch(),
            page: Int,
            size: Int,
        ): ApplicationSearch? =
            if (page >= 0 && size in 1..MAX_SIZE) {
                filters.copy(text = text?.trim()?.takeIf(String::isNotEmpty), page = page, size = size)
            } else {
                null
            }
    }
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
