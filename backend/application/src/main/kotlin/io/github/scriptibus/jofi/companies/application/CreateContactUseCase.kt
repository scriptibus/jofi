// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.CreateContactPort
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactInput
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock
import java.util.UUID

/**
 * Adds a contact person (spec §5); the contact, its channels and its changelog entry (field names
 * only) are stored together. A company that does not exist is `Invalid` (COMPANY, NOT_FOUND).
 */
class CreateContactUseCase(
    private val contacts: ContactRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : CreateContactPort {
    override fun execute(
        input: ContactInput,
        actor: Actor,
    ): ContactResult<Contact> =
        input.validate().toResult().then { details ->
            val contact = Contact.create(ContactId(UUID.randomUUID()), details, clock.storedNow())
            transactions.inContactTransaction {
                contacts.add(contact).toResult().then {
                    val recorded =
                        changelog.record(
                            contact.id.toEntityRef(),
                            actor,
                            contact.createdAt,
                            describeContact("Created contact", null, details),
                        )
                    contact.contactIf(recorded, "changelog")
                }
            }
        }
}
