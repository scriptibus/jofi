// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.NOW
import io.github.scriptibus.jofi.companies.domain.ChannelInput
import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactChannel
import io.github.scriptibus.jofi.companies.domain.ContactField
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactInput
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.companies.domain.ContactViolation
import io.github.scriptibus.jofi.companies.domain.ViolationKind
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class ContactUseCasesTest {
    private val fixtures = ContactFixtures()
    private val create = CreateContactUseCase(fixtures.contactPort, fixtures.changelog, fixtures.transactions, CLOCK)
    private val update = UpdateContactUseCase(fixtures.contactPort, fixtures.changelog, fixtures.transactions, CLOCK)
    private val get = GetContactUseCase(fixtures.contactPort)
    private val search = SearchContactsUseCase(fixtures.contactPort)
    private val acme = CompanyId(UUID.randomUUID()).also { fixtures.companies += it }
    private val input =
        ContactInput(
            name = "  Erika Mustermann ",
            role = "Recruiter",
            company = acme,
            channels =
                listOf(
                    ChannelInput(ChannelKind.EMAIL, " erika@acme.example ", "work"),
                    ChannelInput(ChannelKind.PHONE, " "),
                    ChannelInput(ChannelKind.PHONE, "030 123"),
                ),
            relationshipNotes = "Met at the fair",
        )

    @Test
    fun `creating stores the normalized contact and records field names without values`() {
        val created = create.execute(input, Actor.Ai).shouldBeInstanceOf<ContactResult.Success<Contact>>().value

        created.details.name shouldBe "Erika Mustermann"
        created.details.channels shouldContainExactly
            listOf(
                ContactChannel(ChannelKind.EMAIL, "erika@acme.example", "work"),
                ContactChannel(ChannelKind.PHONE, "030 123"),
            )
        created.version shouldBe Contact.INITIAL_VERSION
        created.createdAt shouldBe NOW
        fixtures.contacts[created.id] shouldBe created
        val entry = fixtures.entries.single()
        entry.entity shouldBe created.id.toEntityRef()
        entry.actor shouldBe Actor.Ai
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Created contact; fields: name, role, company, channels, relationship notes"
        entry.change.fieldChanges.shouldBeEmpty()
    }

    @Test
    fun `invalid input and an unknown company store nothing`() {
        create.execute(ContactInput(" "), Actor.User) shouldBe
            ContactResult.Invalid(listOf(ContactViolation(ContactField.NAME, ViolationKind.REQUIRED)))
        create.execute(input.copy(company = CompanyId(UUID.randomUUID())), Actor.User) shouldBe
            ContactResult.Invalid(listOf(ContactViolation(ContactField.COMPANY, ViolationKind.NOT_FOUND)))

        fixtures.contacts.size shouldBe 0
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a failing changelog rolls the create back`() {
        fixtures.failingChangelog = true

        create.execute(input, Actor.User) shouldBe ContactResult.StorageFailure("changelog")
        fixtures.contacts.size shouldBe 0
    }

    @Test
    fun `updating replaces details and channels, naming only the changed fields`() {
        val contact = fixtures.contact()
        val edit =
            ContactInput(
                "Erika Mustermann",
                "Head of Talent",
                channels = listOf(ChannelInput(ChannelKind.WEB, "https://www.linkedin.com/in/erika")),
            )

        val edited =
            update.execute(contact.id, edit, 0, Actor.User).shouldBeInstanceOf<ContactResult.Success<Contact>>().value

        edited.version shouldBe 1
        edited.updatedAt shouldBe NOW
        edited.details.channels shouldContainExactly
            listOf(ContactChannel(ChannelKind.WEB, "https://www.linkedin.com/in/erika"))
        fixtures.contacts[contact.id] shouldBe edited
        val description =
            fixtures.entries
                .single()
                .change.description
        description shouldBe "Edited contact; fields: role, channels"
        description shouldNotContain "Head of Talent"
        fixtures.entries
            .single()
            .change.fieldChanges
            .shouldBeEmpty()
    }

    @Test
    fun `an unchanged edit is a no-op, a stale version a conflict even then`() {
        val contact = fixtures.contact()
        val same =
            ContactInput(
                "Erika Mustermann",
                "Recruiter",
                channels = listOf(ChannelInput(ChannelKind.EMAIL, "erika@acme.example")),
            )

        update.execute(contact.id, same, 0, Actor.User) shouldBe ContactResult.Success(contact)
        update.execute(contact.id, same, 1, Actor.User) shouldBe ContactResult.VersionConflict
        update.execute(ContactId(UUID.randomUUID()), same, 0, Actor.User) shouldBe ContactResult.NotFound
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `the version is checked before the input and the store catches a race`() {
        val contact = fixtures.contact()

        update.execute(contact.id, ContactInput(" "), 7, Actor.User) shouldBe ContactResult.VersionConflict
        fixtures.concurrentVersion = 1
        update.execute(contact.id, ContactInput("Erika M."), 0, Actor.User) shouldBe ContactResult.VersionConflict
        fixtures.contacts[contact.id] shouldBe contact
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `moving a contact to a company that no longer exists is invalid`() {
        val contact = fixtures.contact()

        update.execute(
            contact.id,
            ContactInput("Erika", company = CompanyId(UUID.randomUUID())),
            0,
            Actor.User,
        ) shouldBe
            ContactResult.Invalid(listOf(ContactViolation(ContactField.COMPANY, ViolationKind.NOT_FOUND)))
        fixtures.contacts[contact.id] shouldBe contact
    }

    @Test
    fun `get and search read the store`() {
        val erika = fixtures.contact("Erika Mustermann")
        fixtures.contact("Max Mustermann")

        get.execute(erika.id) shouldBe ContactResult.Success(erika)
        get.execute(ContactId(UUID.randomUUID())) shouldBe ContactResult.NotFound
        search.execute(ContactSearch("erika")) shouldBe ContactResult.Success(CompanyPage(listOf(erika), 1))
        search.execute(ContactSearch(company = acme)) shouldBe ContactResult.Success(CompanyPage(emptyList(), 0))
    }
}
