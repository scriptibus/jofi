// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port

import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.companies.domain.ContactStoreResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/**
 * Stores contacts (tables `contact` and `contact_channel`; implemented with the use cases in #89).
 * The use case that changes a contact appends its changelog entry in the same transaction
 * (`TransactionPort`). Implementations never throw and never log row data (third-party personal data).
 * The company delete reads the contacts it cascades to through `CompanyRepositoryPort.findContactIds` (#88).
 */
interface ContactRepositoryPort {
    /**
     * Stores a new contact with its channels. A company that does not exist is rejected by
     * `contact_company_fk`, mapped **by constraint name** to [ContactStoreResult.CompanyNotFound].
     */
    fun add(contact: Contact): ContactStoreResult<Unit>

    /**
     * Replaces the stored contact and all its channels if its version is exactly one below
     * [contact]'s (see [Contact.edit]); [ContactStoreResult.VersionConflict] if someone else changed it
     * meanwhile, [ContactStoreResult.CompanyNotFound] as for [add].
     */
    fun update(contact: Contact): ContactStoreResult<Unit>

    fun findById(id: ContactId): ContactStoreResult<Contact>

    fun search(search: ContactSearch): ContactStoreResult<CompanyPage<Contact>>

    /**
     * Deletes the contact and, by `ON DELETE CASCADE`, its channels: nothing personal is left
     * (spec §13). [proof] is what the confirmation gate returned (ADR-0039): the adapter answers
     * [ContactStoreResult.NotConfirmed] unless `proof.covers(Contact.DELETE_OPERATION, id.value.toString())`.
     */
    fun delete(
        id: ContactId,
        proof: ConfirmationResult.Confirmed,
    ): ContactStoreResult<Unit>
}
