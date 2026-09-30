// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.UpdateContactPort
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactInput
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Replaces all details of a contact, channels included. The version is checked first; unchanged
 * details store nothing and write no changelog entry. The changelog names the changed fields, never
 * their values. Read and write share one transaction, and the repository's version check catches a
 * change that slipped in between.
 */
class UpdateContactUseCase(
    private val contacts: ContactRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : UpdateContactPort {
    override fun execute(
        id: ContactId,
        input: ContactInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ContactResult<Contact> =
        transactions.inContactTransaction {
            contacts
                .findById(id)
                .toResult()
                .then { it.basedOn(basedOnVersion) }
                .then { current -> input.validate().toResult().then { edit(current, it, actor) } }
        }

    private fun edit(
        current: Contact,
        details: ContactDetails,
        actor: Actor,
    ): ContactResult<Contact> {
        val edited = current.edit(details, clock.storedNow())
        if (edited == current) return ContactResult.Success(current)
        return contacts.update(edited).toResult().then {
            val recorded =
                changelog.record(
                    edited.id.toEntityRef(),
                    actor,
                    edited.updatedAt,
                    describeContact("Edited contact", current.details, details),
                )
            edited.contactIf(recorded, "changelog")
        }
    }
}
