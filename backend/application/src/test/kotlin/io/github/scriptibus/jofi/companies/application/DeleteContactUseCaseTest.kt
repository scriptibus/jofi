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
import io.kotest.matchers.maps.shouldBeEmpty
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
            fixtures.findLinks,
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
        required.action.effect shouldBe
            ConfirmationEffect(
                "contact",
                "Erika Mustermann",
                mapOf("applications" to 2, "interviews" to 0, "tasks" to 0),
            )
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
    fun `the confirmed repeat records each interview the contact took part in, by id only, as the deleting actor`() {
        val contact = fixtures.contact("Erika Mustermann")
        val applications = linksOf(contact, 1)
        val interviews = List(2) { EntityRef("interview", UUID.randomUUID().toString()) }
        fixtures.participations[contact.id] = interviews
        val client = ConfirmationRequester(Actor.ExternalClient("claude-desktop"), "mcp-1")

        firstStep(contact.id, client).action.effect.counts shouldBe
            mapOf("applications" to 1, "interviews" to 2, "tasks" to 0)
        delete.execute(contact.id, client, firstStep(contact.id, client).token) shouldBe ContactResult.Success(Unit)

        fixtures.participations.size shouldBe 0
        fixtures.entries.map { it.entity } shouldContainExactly
            listOf(contact.id.toEntityRef()) + applications + interviews
        fixtures.entries.map { it.actor }.toSet() shouldBe setOf(Actor.ExternalClient("claude-desktop"))
        fixtures.entries.takeLast(2).forEach {
            it.change.fieldChanges shouldContainExactly
                listOf(FieldChange("participants", contact.id.value.toString(), null))
            it.change.toString() shouldNotContain "Erika"
        }
    }

    private fun tasksOf(
        contact: Contact,
        count: Int,
    ): List<EntityRef> =
        List(count) { EntityRef("task", UUID.randomUUID().toString()) }
            .also { fixtures.linkedTasks[contact.id.value] = it }

    @Test
    fun `the confirmed repeat records each task whose link it clears, by id only, as the deleting actor`() {
        val contact = fixtures.contact("Erika Mustermann")
        val applications = linksOf(contact, 1)
        val tasks = tasksOf(contact, 2)
        val client = ConfirmationRequester(Actor.ExternalClient("claude-desktop"), "mcp-1")

        firstStep(contact.id, client).action.effect.counts shouldBe
            mapOf("applications" to 1, "interviews" to 0, "tasks" to 2)
        delete.execute(contact.id, client, firstStep(contact.id, client).token) shouldBe ContactResult.Success(Unit)

        fixtures.linkedTasks.shouldBeEmpty()
        fixtures.entries.map { it.entity } shouldContainExactly listOf(contact.id.toEntityRef()) + applications + tasks
        fixtures.entries.map { it.actor }.toSet() shouldBe setOf(Actor.ExternalClient("claude-desktop"))
        fixtures.entries.takeLast(2).forEach {
            it.change.description shouldBe "Cleared the link to a deleted contact"
            it.change.fieldChanges shouldContainExactly
                listOf(FieldChange("link", "contact:${contact.id.value}", null))
            it.change.toString() shouldNotContain "Erika"
        }
    }

    @Test
    fun `a new task link between the steps voids the token`() {
        val contact = fixtures.contact()
        val token = firstStep(contact.id).token
        tasksOf(contact, 1)

        delete.execute(contact.id, user, token) shouldBe
            ContactResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))
        fixtures.contacts.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `unreadable task links or a failing task entry delete nothing`() {
        val contact = fixtures.contact()
        tasksOf(contact, 1)
        fixtures.taskLinksAvailable = false
        delete.execute(contact.id, user, ConfirmationToken("any")) shouldBe
            ContactResult.StorageFailure("read linked tasks")

        fixtures.taskLinksAvailable = true
        fixtures.failingChangelogFor = "task"
        delete.execute(contact.id, user, firstStep(contact.id).token) shouldBe ContactResult.StorageFailure("changelog")

        fixtures.contacts.size shouldBe 1
        fixtures.linkedTasks.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `a rename, a new link or a new participation between the steps voids the token, other edits do not`() {
        val contact = fixtures.contact("Erika Mustermann")
        val renamed = firstStep(contact.id).token
        fixtures.contacts[contact.id] = contact.copy(details = ContactDetails("Erika Musterfrau"))
        delete.execute(contact.id, user, renamed) shouldBe
            ContactResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))

        val linked = firstStep(contact.id).token
        linksOf(contact, 1)
        delete.execute(contact.id, user, linked) shouldBe
            ContactResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))

        val participating = firstStep(contact.id).token
        fixtures.participations[contact.id] = listOf(EntityRef("interview", UUID.randomUUID().toString()))
        delete.execute(contact.id, user, participating) shouldBe
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
