// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactChannel
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.companies.domain.ContactStoreResult
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import java.util.UUID

/**
 * An in-memory contact store (plus the changelog, events and confirmation gate of [CompanyFixtures])
 * with a transaction that restores contacts, links, changelog and events when the result is not committed.
 */
class ContactFixtures {
    private val shared = CompanyFixtures()
    val contacts = linkedMapOf<ContactId, Contact>()

    /** The companies `contact_company_fk` accepts. */
    val companies = mutableSetOf<CompanyId>()

    /** Application links per contact, as the applications context reports them. */
    val links = mutableMapOf<ContactId, List<EntityRef>>()

    /** Interviews per contact it takes part in, as the applications context reports them. */
    val participations = mutableMapOf<ContactId, List<EntityRef>>()
    val entries get() = shared.entries
    val events get() = shared.events
    val changelog get() = shared.changelog
    val eventPort get() = shared.eventPort
    val confirmation get() = shared.confirmation
    var linksAvailable = true
    var failingChangelog by shared::failingChangelog
    var failingEvents by shared::failingEvents

    /** A version another client stored between this use case's read and its write (the update race). */
    var concurrentVersion: Long? = null

    val contactPort =
        object : ContactRepositoryPort {
            override fun add(contact: Contact): ContactStoreResult<Unit> {
                if (!knows(contact.details.company)) return ContactStoreResult.CompanyNotFound
                contacts[contact.id] = contact
                return ContactStoreResult.Success(Unit)
            }

            override fun update(contact: Contact): ContactStoreResult<Unit> {
                val stored = contacts[contact.id]
                return when {
                    stored == null -> ContactStoreResult.NotFound
                    (concurrentVersion ?: stored.version) != contact.version - 1 -> ContactStoreResult.VersionConflict
                    !knows(contact.details.company) -> ContactStoreResult.CompanyNotFound
                    else -> ContactStoreResult.Success(Unit).also { contacts[contact.id] = contact }
                }
            }

            override fun findById(id: ContactId) =
                contacts[id]?.let { ContactStoreResult.Success(it) } ?: ContactStoreResult.NotFound

            override fun search(search: ContactSearch): ContactStoreResult<CompanyPage<Contact>> {
                val found =
                    contacts.values.filter { contact ->
                        search.text.let { it == null || contact.details.name.contains(it, ignoreCase = true) } &&
                            (search.company == null || contact.details.company == search.company)
                    }
                val page = found.drop(search.page * search.size).take(search.size)
                return ContactStoreResult.Success(CompanyPage(page, found.size.toLong()))
            }

            override fun delete(
                id: ContactId,
                proof: ConfirmationResult.Confirmed,
            ): ContactStoreResult<Unit> =
                when {
                    !proof.covers(Contact.DELETE_OPERATION, id.value.toString()) -> {
                        ContactStoreResult.NotConfirmed
                    }

                    contacts.remove(id) == null -> {
                        ContactStoreResult.NotFound
                    }

                    else -> {
                        ContactStoreResult.Success(Unit).also {
                            links.remove(id)
                            participations.remove(id)
                        }
                    }
                }
        }

    val linkedApplications =
        object : LinkedApplicationsPort {
            override fun linkedTo(contact: UUID): LinkedApplicationsPort.Linked =
                if (linksAvailable) {
                    LinkedApplicationsPort.Linked.Found(
                        links[ContactId(contact)].orEmpty(),
                        participations[ContactId(contact)].orEmpty(),
                    )
                } else {
                    LinkedApplicationsPort.Linked.Unavailable
                }
        }

    val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T {
                val contactsBefore = contacts.toMap()
                val linksBefore = links.toMap()
                val result = shared.transactions.inTransaction(commitIf, work)
                if (!commitIf(result)) {
                    contacts.clear()
                    contacts.putAll(contactsBefore)
                    links.clear()
                    links.putAll(linksBefore)
                }
                return result
            }
        }

    fun contact(
        name: String = "Erika Mustermann",
        version: Long = Contact.INITIAL_VERSION,
    ): Contact {
        val details =
            ContactDetails(
                name,
                "Recruiter",
                channels = listOf(ContactChannel(ChannelKind.EMAIL, "erika@acme.example")),
            )
        val contact =
            Contact
                .create(ContactId(UUID.randomUUID()), details, CompanyFixtures.CREATED)
                .copy(version = version)
        contacts[contact.id] = contact
        return contact
    }

    private fun knows(company: CompanyId?): Boolean = company == null || company in companies
}
