// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port.inbound

import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactInput
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken

// Inbound ports (ADR-0041) for contact persons, implemented by the use cases of the same name (#89).
// Mutations take the acting `Actor` and record it in the changelog (spec §13), with field names but
// never the values: contacts are third-party personal data and the changelog is append-only.
// `basedOnVersion` is the `Contact.version` the caller last read: a stale one is `VersionConflict`,
// checked first. A `ContactInput.company` that does not exist is `Invalid` (COMPANY, NOT_FOUND).
// Timestamps are `clock.instant().truncatedTo(ChronoUnit.MICROS)`.

interface CreateContactPort {
    fun execute(
        input: ContactInput,
        actor: Actor,
    ): ContactResult<Contact>
}

/**
 * Replaces **all** details, channels included, with [input] (a PUT, not a patch): a field or channel
 * left out is removed. Unchanged details store nothing and write no changelog entry.
 */
interface UpdateContactPort {
    fun execute(
        id: ContactId,
        input: ContactInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ContactResult<Contact>
}

interface GetContactPort {
    fun execute(id: ContactId): ContactResult<Contact>
}

interface SearchContactsPort {
    fun execute(search: ContactSearch): ContactResult<CompanyPage<Contact>>
}

/**
 * Deletes a contact with all its personal data in two steps (ADR-0039): without [token] it answers
 * [ContactResult.Unconfirmed] with a token bound to [Contact.DELETE_OPERATION], the contact id and the
 * effect `ConfirmationEffect("contact", <name>)` (#90 adds the count of linked applications). With the
 * token, it deletes and publishes `ContactDeleted`; the changelog entry names no personal data.
 * The actor is [requester]'s.
 */
interface DeleteContactPort {
    fun execute(
        id: ContactId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ContactResult<Unit>
}
