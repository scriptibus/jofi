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

    /** The input breaks the rules of [CompanyInput] or [PreferenceInput]; nothing was changed. */
    data class Invalid(
        val violations: List<CompanyViolation>,
    ) : CompanyResult<Nothing>

    data object NotFound : CompanyResult<Nothing>

    /** The change was based on an older version of the company; nothing was changed. */
    data object VersionConflict : CompanyResult<Nothing>

    /** A company with applications cannot be deleted. */
    data object HasApplications : CompanyResult<Nothing>

    /** The delete needs (another) confirmation step (ADR-0039); nothing was deleted. */
    data class Unconfirmed(
        val outcome: ConfirmationResult.Unconfirmed,
    ) : CompanyResult<Nothing>

    /** The store could not complete [operation]; nothing was changed. */
    data class StorageFailure(
        val operation: String,
    ) : CompanyResult<Nothing>
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
    }
}

/** One page of companies and the number of all companies matching the search. */
data class CompanyPage(
    val companies: List<Company>,
    val total: Long,
) {
    init {
        require(total >= companies.size) { "The total cannot be smaller than the page" }
    }
}
