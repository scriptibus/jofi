// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.NOW
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactDeleted
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class DeleteContactUseCaseTest {
    private val fixtures = ContactFixtures()
    private val delete =
        DeleteContactUseCase(
            fixtures.contactPort,
            fixtures.linkedApplications,
            fixtures.confirmation,
            fixtures.eventPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val user = ConfirmationRequester(Actor.User, "session-1")

    private fun firstStep(
        id: ContactId,
        requester: ConfirmationRequester = user,
    ): ConfirmationResult.Required {
        val result = delete.execute(id, requester, null).shouldBeInstanceOf<ContactResult.Unconfirmed>()
        return result.outcome.shouldBeInstanceOf<ConfirmationResult.Required>()
    }

    private fun linksOf(
        contact: Contact,
        count: Int,
    ): List<EntityRef> =
        List(count) { EntityRef("application", UUID.randomUUID().toString()) }
            .also { fixtures.links[contact.id] = it }

    @Test
    fun `the first call only asks, counting the linked applications`() {
        val contact = fixtures.contact("Erika Mustermann")
        linksOf(contact, 2)

        val required = firstStep(contact.id)

        required.action.operation shouldBe Contact.DELETE_OPERATION
        required.action.targets shouldBe listOf(contact.id.value.toString())
        required.action.effect shouldBe ConfirmationEffect("contact", "Erika Mustermann", mapOf("applications" to 2))
        fixtures.contacts.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `the confirmed repeat deletes and records the contact and each application by id only`() {
        val contact = fixtures.contact("Erika Mustermann")
        val applications = linksOf(contact, 2)
        val ai = ConfirmationRequester(Actor.Ai, "chat-7")

        delete.execute(contact.id, ai, firstStep(contact.id, ai).token) shouldBe ContactResult.Success(Unit)

        fixtures.contacts.size shouldBe 0
        fixtures.links.size shouldBe 0
        fixtures.entries.map { it.entity } shouldContainExactly listOf(contact.id.toEntityRef()) + applications
        fixtures.entries.map { it.actor }.toSet() shouldBe setOf(Actor.Ai)
        fixtures.entries.map { it.occurredAt }.toSet() shouldBe setOf(NOW)
        fixtures.entries
            .first()
            .change.fieldChanges
            .shouldBeEmpty()
        fixtures.entries.drop(1).forEach {
            it.change.fieldChanges shouldContainExactly
                listOf(FieldChange("contacts", contact.id.value.toString(), null))
        }
        fixtures.entries.forEach { it.change.toString() shouldNotContain "Erika" }
        fixtures.events shouldContainExactly listOf(ContactDeleted(contact.id, Actor.Ai, NOW))
    }

    @Test
    fun `a rename or a new link between the steps voids the token, other edits do not`() {
        val contact = fixtures.contact("Erika Mustermann")
        val renamed = firstStep(contact.id).token
        fixtures.contacts[contact.id] = contact.copy(details = ContactDetails("Erika Musterfrau"))
        delete.execute(contact.id, user, renamed) shouldBe
            ContactResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))

        val linked = firstStep(contact.id).token
        linksOf(contact, 1)
        delete.execute(contact.id, user, linked) shouldBe
            ContactResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))

        val token = firstStep(contact.id).token
        fixtures.contacts[contact.id] = contact.copy(details = ContactDetails("Erika Musterfrau", role = "CTO"))
        delete.execute(contact.id, user, token) shouldBe ContactResult.Success(Unit)
    }

    @Test
    fun `a token works once, only in its session and only for its contact`() {
        val contact = fixtures.contact()
        val other = fixtures.contact("Max Mustermann")
        val stolen = firstStep(contact.id).token
        val retargeted = firstStep(contact.id).token

        delete.execute(contact.id, ConfirmationRequester(Actor.User, "session-2"), stolen) shouldBe
            ContactResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))
        delete.execute(other.id, user, retargeted) shouldBe
            ContactResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))
        val token = firstStep(contact.id).token
        delete.execute(contact.id, user, token) shouldBe ContactResult.Success(Unit)
        delete.execute(contact.id, user, token) shouldBe ContactResult.NotFound
        fixtures.contacts.keys shouldContainExactly setOf(other.id)
    }

    @Test
    fun `unknown contacts and unreadable links delete nothing`() {
        delete.execute(ContactId(UUID.randomUUID()), user, null) shouldBe ContactResult.NotFound
        val contact = fixtures.contact()
        fixtures.linksAvailable = false

        delete.execute(contact.id, user, ConfirmationToken("any")) shouldBe
            ContactResult.StorageFailure("read linked applications")
        fixtures.contacts.size shouldBe 1
    }

    @Test
    fun `a failing changelog or event rolls the whole delete back`() {
        val contact = fixtures.contact()
        linksOf(contact, 1)
        fixtures.failingChangelog = true
        delete.execute(contact.id, user, firstStep(contact.id).token) shouldBe ContactResult.StorageFailure("changelog")
        fixtures.failingChangelog = false
        fixtures.failingEvents = true

        delete.execute(contact.id, user, firstStep(contact.id).token) shouldBe
            ContactResult.StorageFailure("publish event")

        fixtures.contacts.size shouldBe 1
        fixtures.links.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }
}
