// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactField
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactStoreResult
import io.github.scriptibus.jofi.companies.domain.ContactValidation
import io.github.scriptibus.jofi.companies.domain.ContactViolation
import io.github.scriptibus.jofi.companies.domain.ViolationKind
import io.github.scriptibus.jofi.shared.application.port.TransactionPort

// How the contact use cases chain their steps (as `CompanySteps`) and describe their changes: a
// ContactResult per step, the first failure ends the chain.

/** Continues with [next] on success; a failure passes through unchanged. */
internal inline fun <T, R> ContactResult<T>.then(next: (T) -> ContactResult<R>): ContactResult<R> =
    when (this) {
        is ContactResult.Success -> next(value)
        is ContactResult.Failure -> this
    }

/** Runs [work] in one transaction that commits only on [ContactResult.Success]. */
internal fun <T> TransactionPort.inContactTransaction(work: () -> ContactResult<T>): ContactResult<T> =
    inTransaction({ it is ContactResult.Success }, work)

internal fun <T> ContactValidation<T>.toResult(): ContactResult<T> =
    when (this) {
        is ContactValidation.Valid -> ContactResult.Success(value)
        is ContactValidation.Invalid -> ContactResult.Invalid(violations)
    }

internal fun <T> ContactStoreResult<T>.toResult(): ContactResult<T> =
    when (this) {
        is ContactStoreResult.Success -> {
            ContactResult.Success(value)
        }

        ContactStoreResult.NotFound -> {
            ContactResult.NotFound
        }

        ContactStoreResult.VersionConflict -> {
            ContactResult.VersionConflict
        }

        // `contact_company_fk`: the input named a company that does not exist (any more).
        ContactStoreResult.CompanyNotFound -> {
            ContactResult.Invalid(
                listOf(ContactViolation(ContactField.COMPANY, ViolationKind.NOT_FOUND)),
            )
        }

        // Only a proof for another target gets here, a bug of the use case; nothing was deleted.
        ContactStoreResult.NotConfirmed -> {
            ContactResult.StorageFailure("delete without matching proof")
        }

        is ContactStoreResult.StorageFailure -> {
            ContactResult.StorageFailure(operation)
        }
    }

/** The contact if the caller based its change on its current version, else [ContactResult.VersionConflict]. */
internal fun Contact.basedOn(version: Long): ContactResult<Contact> =
    if (this.version == version) ContactResult.Success(this) else ContactResult.VersionConflict

/** [this] if [done] holds, else a storage failure of [operation] (the transaction then rolls back). */
internal fun <T> T.contactIf(
    done: Boolean,
    operation: String,
): ContactResult<T> = if (done) ContactResult.Success(this) else ContactResult.StorageFailure(operation)

/**
 * The changelog description of [action] from [before] to [after]: the names of the fields that
 * changed, never their values, since contacts are third-party personal data and the changelog is
 * append-only (ADR-0041). A new contact ([before] `null`) names the fields it was created with.
 */
internal fun describeContact(
    action: String,
    before: ContactDetails?,
    after: ContactDetails,
): String {
    val fields =
        listOf<Pair<String, (ContactDetails) -> Any?>>(
            "name" to { it.name },
            "role" to { it.role },
            "company" to { it.company },
            "channels" to { it.channels.ifEmpty { null } },
            "relationship notes" to { it.relationshipNotes },
        ).filter { (_, value) -> before?.let(value) != value(after) }
            .map { (name, _) -> name }
    return if (fields.isEmpty()) action else "$action; fields: ${fields.joinToString()}"
}
