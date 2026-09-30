// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ContactTest {
    private val id = ContactId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
    private val companyId = CompanyId(UUID.fromString("00000000-0000-0000-0000-00000000000c"))
    private val created = Instant.parse("2026-09-30T08:00:00Z")
    private val later = created.plusSeconds(60)
    private val details =
        ContactDetails(
            name = "Erika Mustermann",
            role = "Recruiter",
            company = companyId,
            channels =
                listOf(
                    ContactChannel(ChannelKind.EMAIL, "erika@acme.example", "work"),
                    ContactChannel(ChannelKind.PHONE, "+49 30 1234567"),
                ),
            relationshipNotes = "Met at the job fair.",
        )
    private val contact = Contact.create(id, details, created)

    @Test
    fun `a new contact has the initial version and was never updated`() {
        contact.version shouldBe Contact.INITIAL_VERSION
        contact.createdAt shouldBe created
        contact.updatedAt shouldBe created
    }

    @Test
    fun `changelog entries refer to a contact by the registered entity type`() {
        id.toEntityRef() shouldBe EntityRef("contact", "00000000-0000-0000-0000-0000000000c1")
        ContactId.ENTITY_TYPE shouldBe "contact"
        Contact.DELETE_OPERATION shouldBe "contacts.delete"
    }

    @Test
    fun `editing replaces the details and counts the version up`() {
        val edited = contact.edit(details.copy(role = "Head of Talent"), later)

        edited.details.role shouldBe "Head of Talent"
        edited.version shouldBe 1
        edited.createdAt shouldBe created
        edited.updatedAt shouldBe later
    }

    @Test
    fun `editing to the same details changes nothing, not even the version`() {
        contact.edit(details.copy(), later) shouldBeSameInstanceAs contact
    }

    @Test
    fun `a contact is never updated before it was created and has no negative version`() {
        shouldThrow<IllegalArgumentException> { contact.copy(updatedAt = created.minusSeconds(1)) }
        shouldThrow<IllegalArgumentException> { contact.copy(version = -1) }
    }

    @Test
    fun `nothing that describes a contact prints personal data`() {
        val printed =
            listOf(
                contact.toString(),
                details.toString(),
                details.channels.joinToString(),
                ContactInput("Erika Mustermann", "Recruiter", relationshipNotes = "Met").toString(),
                ChannelInput(ChannelKind.EMAIL, "erika@acme.example", "work").toString(),
                ContactSearch(text = "Erika").toString(),
            ).joinToString()

        listOf("Erika", "Mustermann", "Recruiter", "erika@", "1234567", "work", "fair", "Met").forEach {
            printed shouldNotContain it
        }
    }

    @Test
    fun `the deletion event carries only the id, the actor and the time`() {
        ContactDeleted(id, Actor.User, later).toString() shouldNotContain "Erika"
    }

    @Test
    fun `details guard their invariants against programming errors`() {
        shouldThrow<IllegalArgumentException> { ContactDetails(" Erika") }
        shouldThrow<IllegalArgumentException> { ContactDetails("x".repeat(ContactDetails.MAX_NAME_LENGTH + 1)) }
        shouldThrow<IllegalArgumentException> { ContactDetails("Erika", role = "") }
        shouldThrow<IllegalArgumentException> { details.copy(relationshipNotes = "Notes\n") }
        shouldThrow<IllegalArgumentException> {
            details.copy(channels = details.channels + details.channels.first())
        }
        shouldThrow<IllegalArgumentException> {
            details.copy(channels = (0..ContactDetails.MAX_CHANNELS).map { ContactChannel(ChannelKind.PHONE, "$it") })
        }
        shouldThrow<IllegalArgumentException> { ContactChannel(ChannelKind.EMAIL, "erika") }
        shouldThrow<IllegalArgumentException> { ContactChannel(ChannelKind.OTHER, "@erika", " work") }
    }

    @Test
    fun `details at exactly every limit are fine`() {
        ContactDetails(
            name = "n".repeat(ContactDetails.MAX_NAME_LENGTH),
            role = "r".repeat(ContactDetails.MAX_ROLE_LENGTH),
            channels =
                (1..ContactDetails.MAX_CHANNELS).map {
                    ContactChannel(ChannelKind.PHONE, "$it", "l".repeat(ContactChannel.MAX_LABEL_LENGTH))
                },
            relationshipNotes = "x".repeat(ContactDetails.MAX_NOTES_LENGTH),
        ).channels.size shouldBe ContactDetails.MAX_CHANNELS
    }

    @Test
    fun `a search has a non-blank text, a page from zero and a bounded size`() {
        ContactSearch.of("  erika ", companyId, 1, 20) shouldBe ContactSearch("erika", companyId, 1, 20)
        ContactSearch.of(" ", null, 0, ContactSearch.DEFAULT_SIZE) shouldBe ContactSearch()
        ContactSearch.of(null, null, -1, 20) shouldBe null
        ContactSearch.of(null, null, 0, ContactSearch.MAX_SIZE + 1) shouldBe null
        shouldThrow<IllegalArgumentException> { ContactSearch(text = " ") }
        shouldThrow<IllegalArgumentException> { ContactSearch(size = 0) }
    }

    @Test
    fun `only channel violations name a channel position`() {
        ContactViolation(ContactField.CHANNEL_VALUE, ViolationKind.INVALID_EMAIL, 2).channel shouldBe 2
        shouldThrow<IllegalArgumentException> { ContactViolation(ContactField.CHANNEL_LABEL, ViolationKind.TOO_LONG) }
        shouldThrow<IllegalArgumentException> { ContactViolation(ContactField.NAME, ViolationKind.REQUIRED, 0) }
        shouldThrow<IllegalArgumentException> {
            ContactViolation(
                ContactField.CHANNEL_VALUE,
                ViolationKind.REQUIRED,
                -1,
            )
        }
    }
}
