// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/**
 * Outcome of a contact use case (#89). Callers map every case: the REST controller to a status and
 * problem type, an MCP tool to a tool error.
 */
sealed interface ContactResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : ContactResult<T>

    /** Every outcome but [Success]: nothing was changed. */
    sealed interface Failure : ContactResult<Nothing>

    /**
     * The input breaks the rules of [ContactInput], or names a company that does not exist
     * ([ContactField.COMPANY], [ViolationKind.NOT_FOUND]); nothing was changed.
     */
    data class Invalid(
        val violations: List<ContactViolation>,
    ) : Failure

    data object NotFound : Failure

    /** The change was based on an older version of the contact; nothing was changed. */
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

/** Outcome of a contact repository call; storage failures are values, not exceptions. */
sealed interface ContactStoreResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : ContactStoreResult<T>

    /** No contact with the requested id. */
    data object NotFound : ContactStoreResult<Nothing>

    /** The stored contact has a newer version than the change was based on; reload and retry. */
    data object VersionConflict : ContactStoreResult<Nothing>

    /** The contact's company does not exist (any more): `contact_company_fk` rejected it. */
    data object CompanyNotFound : ContactStoreResult<Nothing>

    /** The confirmation proof does not cover deleting this contact; nothing was deleted. */
    data object NotConfirmed : ContactStoreResult<Nothing>

    /** The store could not complete [operation]. Carries no row data, so it is safe to log. */
    data class StorageFailure(
        val operation: String,
    ) : ContactStoreResult<Nothing>
}

/**
 * A page of the contact list: [text] matches names fuzzily (pg_trgm similarity, best match first,
 * otherwise by name), [company] keeps the contacts of one company. [page] counts from 0.
 */
data class ContactSearch(
    val text: String? = null,
    val company: CompanyId? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        require(text == null || text.isNotBlank()) { "A search text, when given, must not be blank" }
        require(page >= 0) { "A page number must not be negative" }
        require(size in 1..MAX_SIZE) { "A page holds 1 to $MAX_SIZE contacts" }
    }

    override fun toString(): String = "ContactSearch(company=$company, page=$page, size=$size)"

    companion object {
        const val DEFAULT_SIZE = 50
        const val MAX_SIZE = 200

        /**
         * The search for raw query parameters, or `null` if [page] or [size] is out of range (the
         * caller answers 400). Blank [text] searches every contact.
         */
        fun of(
            text: String?,
            company: CompanyId?,
            page: Int,
            size: Int,
        ): ContactSearch? =
            if (page >= 0 && size in 1..MAX_SIZE) {
                ContactSearch(text?.trim()?.takeIf(String::isNotEmpty), company, page, size)
            } else {
                null
            }
    }
}
