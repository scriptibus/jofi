// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/**
 * Outcome of a company use case (#88). Callers map every case: the REST controller to a status and
 * problem type, an MCP tool to a tool error.
 */
sealed interface CompanyResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : CompanyResult<T>

    /** Every outcome but [Success]: nothing was changed. */
    sealed interface Failure : CompanyResult<Nothing>

    /** The input breaks the rules of [CompanyInput] or [PreferenceInput]; nothing was changed. */
    data class Invalid(
        val violations: List<CompanyViolation>,
    ) : Failure

    data object NotFound : Failure

    /** The change was based on an older version of the company; nothing was changed. */
    data object VersionConflict : Failure

    /** A company with applications cannot be deleted. */
    data object HasApplications : Failure

    /** The delete needs (another) confirmation step (ADR-0039); nothing was deleted. */
    data class Unconfirmed(
        val outcome: ConfirmationResult.Unconfirmed,
    ) : Failure

    /** The store could not complete [operation]; nothing was changed. */
    data class StorageFailure(
        val operation: String,
    ) : Failure
}

/** Outcome of a company repository call; storage failures are values, not exceptions. */
sealed interface CompanyStoreResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : CompanyStoreResult<T>

    /** No company with the requested id. */
    data object NotFound : CompanyStoreResult<Nothing>

    /** The stored company has a newer version than the change was based on; reload and retry. */
    data object VersionConflict : CompanyStoreResult<Nothing>

    /** The company still has applications, so it cannot be deleted. */
    data object HasApplications : CompanyStoreResult<Nothing>

    /** The confirmation proof does not cover deleting this company; nothing was deleted. */
    data object NotConfirmed : CompanyStoreResult<Nothing>

    /** The store could not complete [operation]. Carries no row data, so it is safe to log. */
    data class StorageFailure(
        val operation: String,
    ) : CompanyStoreResult<Nothing>
}

/**
 * A page of the company list: [text] matches names fuzzily (pg_trgm similarity, best match first,
 * otherwise by name), [preference] filters by preference. [page] counts from 0.
 */
data class CompanySearch(
    val text: String? = null,
    val preference: PreferenceKind? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        require(text == null || text.isNotBlank()) { "A search text, when given, must not be blank" }
        require(page >= 0) { "A page number must not be negative" }
        require(size in 1..MAX_SIZE) { "A page holds 1 to $MAX_SIZE companies" }
    }

    companion object {
        const val DEFAULT_SIZE = 50
        const val MAX_SIZE = 200

        /**
         * The search for raw query parameters, or `null` if [page] or [size] is out of range (the
         * caller answers 400). Blank [text] searches every company.
         */
        fun of(
            text: String?,
            preference: PreferenceKind?,
            page: Int,
            size: Int,
        ): CompanySearch? =
            if (page >= 0 && size in 1..MAX_SIZE) {
                CompanySearch(text?.trim()?.takeIf(String::isNotEmpty), preference, page, size)
            } else {
                null
            }
    }
}

/** One page of [items] and the number of all items matching the search. */
data class CompanyPage<out T>(
    val items: List<T>,
    val total: Long,
) {
    init {
        require(total >= items.size) { "The total cannot be smaller than the page" }
    }
}

/**
 * A company as the use cases answer it: with the number of its applications, which the
 * applications context counts (through its public API, #88).
 */
data class CompanyView(
    val company: Company,
    val applicationCount: Int,
) {
    init {
        require(applicationCount >= 0) { "An application count must not be negative" }
    }
}
