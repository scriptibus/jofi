// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows
import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactChannel
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.companies.domain.ContactStoreResult
import io.github.scriptibus.jofi.setup.adapter.persistence.ConfirmedProofs
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT_CHANNEL
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** `ContactRepository` on a real PostgreSQL migrated from zero: round trips, versions, channels, search, delete. */
class ContactRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: ContactRepository
    private lateinit var rows: ApplicationRows

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = ContactRepository(dsl)
        rows = ApplicationRows(dsl)
    }

    private fun stored(
        name: String,
        company: CompanyId? = null,
        channels: List<ContactChannel> = emptyList(),
    ): Contact {
        val contact =
            Contact.create(
                ContactId(UUID.randomUUID()),
                ContactDetails(name, company = company, channels = channels),
                CREATED,
            )
        repository.add(contact) shouldBe ContactStoreResult.Success(Unit)
        return contact
    }

    private fun names(search: ContactSearch): List<String> =
        repository
            .search(search)
            .shouldBeInstanceOf<ContactStoreResult.Success<CompanyPage<Contact>>>()
            .value.items
            .map { it.details.name }

    @Test
    fun `a contact with every field and ordered channels reads back equal`() {
        val details =
            ContactDetails(
                name = "Zoë Ağaoğlu",
                role = "Head of Engineering",
                company = CompanyId(rows.company()),
                channels =
                    listOf(
                        ContactChannel(ChannelKind.PHONE, "+49 30 ١٢٣", "mobile"),
                        ContactChannel(ChannelKind.EMAIL, "\"a@b\"@bücher.example", "work"),
                        ContactChannel(ChannelKind.WEB, "https://www.xing.com/profile/Zoe_Agaoglu"),
                        ContactChannel(ChannelKind.OTHER, "@zoe:matrix.example"),
                    ),
                relationshipNotes = "# Met at the fair",
            )
        val contact = Contact(ContactId(UUID.randomUUID()), details, 3, CREATED, LATER)

        repository.add(contact) shouldBe ContactStoreResult.Success(Unit)

        repository.findById(contact.id) shouldBe ContactStoreResult.Success(contact)
        repository.findById(ContactId(UUID.randomUUID())) shouldBe ContactStoreResult.NotFound
    }

    @Test
    fun `an update stores only on top of its version and replaces all channels`() {
        val contact = stored("Erika", channels = listOf(ContactChannel(ChannelKind.EMAIL, "erika@acme.example")))
        val edited =
            contact.edit(
                ContactDetails(
                    "Erika",
                    channels =
                        listOf(
                            ContactChannel(ChannelKind.PHONE, "030 123"),
                            ContactChannel(ChannelKind.EMAIL, "erika@acme.example", "work"),
                        ),
                ),
                LATER,
            )

        repository.update(edited) shouldBe ContactStoreResult.Success(Unit)
        repository.update(contact.edit(ContactDetails("Erika M."), LATER)) shouldBe ContactStoreResult.VersionConflict
        repository.update(edited) shouldBe ContactStoreResult.VersionConflict

        repository.findById(contact.id) shouldBe ContactStoreResult.Success(edited)
        val cleared = edited.edit(ContactDetails("Erika"), LATER)
        repository.update(cleared) shouldBe ContactStoreResult.Success(Unit)
        dsl.fetchCount(CONTACT_CHANNEL) shouldBe 0
        val unknown = Contact.create(ContactId(UUID.randomUUID()), ContactDetails("Nobody"), CREATED)
        repository.update(unknown.edit(ContactDetails("Still nobody"), LATER)) shouldBe ContactStoreResult.NotFound
    }

    @Test
    fun `a company that does not exist is recognised by the name of the foreign key`() {
        val missing = CompanyId(UUID.randomUUID())

        val contact = Contact.create(ContactId(UUID.randomUUID()), ContactDetails("Erika", company = missing), CREATED)
        repository.add(contact) shouldBe ContactStoreResult.CompanyNotFound
    }

    @Test
    fun `moving a contact to a company that does not exist is refused by name`() {
        val contact = stored("Erika")

        repository.update(contact.edit(ContactDetails("Erika", company = CompanyId(UUID.randomUUID())), LATER)) shouldBe
            ContactStoreResult.CompanyNotFound
    }

    @Test
    fun `search matches names fuzzily, filters by company and pages with the total`() {
        val acme = CompanyId(rows.company())
        stored("Erika Mustermann", acme, listOf(ContactChannel(ChannelKind.EMAIL, "erika@acme.example")))
        stored("Max Mustermann", acme)
        stored("Erica Musterfrau")
        stored("100% Hiring_Team")

        names(ContactSearch(text = "erika")) shouldContainExactly listOf("Erika Mustermann")
        names(ContactSearch(text = "_T")) shouldContainExactly listOf("100% Hiring_Team")
        names(ContactSearch(company = acme)) shouldContainExactly listOf("Erika Mustermann", "Max Mustermann")
        val page =
            repository
                .search(ContactSearch(company = acme, page = 0, size = 1))
                .shouldBeInstanceOf<ContactStoreResult.Success<CompanyPage<Contact>>>()
                .value
        page.items
            .single()
            .details.channels shouldContainExactly
            listOf(ContactChannel(ChannelKind.EMAIL, "erika@acme.example"))
        page.total shouldBe 2
    }

    @Test
    fun `a confirmed delete leaves nothing of the contact, its channels or its links`() {
        val company = rows.company()
        val contact =
            stored("Erika", CompanyId(company), listOf(ContactChannel(ChannelKind.EMAIL, "erika@acme.example")))
        val other = stored("Max", channels = listOf(ContactChannel(ChannelKind.PHONE, "030 123")))
        val application = UUID.randomUUID()
        rows.application(application, company)
        rows.link(application, contact.id.value)
        rows.link(application, other.id.value)

        repository.delete(contact.id, proofFor(contact.id)) shouldBe ContactStoreResult.Success(Unit)

        dsl.fetchCount(CONTACT, CONTACT.ID.eq(contact.id.value)) shouldBe 0
        dsl.fetchCount(CONTACT_CHANNEL, CONTACT_CHANNEL.CONTACT_ID.eq(contact.id.value)) shouldBe 0
        dsl.fetchCount(APPLICATION_CONTACT, APPLICATION_CONTACT.CONTACT_ID.eq(contact.id.value)) shouldBe 0
        dsl.fetchCount(APPLICATION_CONTACT) shouldBe 1
        repository.findById(other.id) shouldBe ContactStoreResult.Success(other)
        repository.delete(contact.id, proofFor(contact.id)) shouldBe ContactStoreResult.NotFound
    }

    @Test
    fun `a delete needs a proof for exactly this contact`() {
        val contact = stored("Erika")
        val other = stored("Max")

        repository.delete(contact.id, proofFor(other.id)) shouldBe ContactStoreResult.NotConfirmed
        repository.delete(contact.id, ConfirmedProofs.of("companies.delete", contact.id.value.toString())) shouldBe
            ContactStoreResult.NotConfirmed
        dsl.fetchCount(CONTACT) shouldBe 2
    }

    @Test
    fun `a failing statement is a storage failure, not an exception`() {
        val contact = stored("Erika")

        repository.add(contact) shouldBe ContactStoreResult.StorageFailure("add")
        ContactRepository(DSL.using(SQLDialect.POSTGRES)).findById(contact.id) shouldBe
            ContactStoreResult.StorageFailure("findById")
    }

    private fun proofFor(id: ContactId) = ConfirmedProofs.of(Contact.DELETE_OPERATION, id.value.toString())

    private companion object {
        val CREATED: Instant = Instant.parse("2026-09-30T08:00:00.123456Z")
        val LATER: Instant = Instant.parse("2026-09-30T09:00:00.654321Z")
    }
}
