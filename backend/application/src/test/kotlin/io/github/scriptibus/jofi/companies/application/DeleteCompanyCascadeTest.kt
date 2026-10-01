// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.NOW
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What the company delete's cascade removes without a trace (#188, ADR-0048): the application links and interview
 * participations of the contacts that go with the company get the entries a single contact delete would write.
 */
class DeleteCompanyCascadeTest {
    private val fixtures = CompanyFixtures()
    private val delete =
        DeleteCompanyUseCase(
            fixtures.companyPort,
            FindCompanyLinksUseCase(fixtures.applicationPort, fixtures.taskLinkPort, fixtures.linkedApplicationsPort),
            fixtures.confirmation,
            fixtures.eventPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val user = ConfirmationRequester(Actor.User, "session-1")

    private fun firstStep(
        company: Company,
        requester: ConfirmationRequester = user,
    ): ConfirmationResult.Required =
        delete
            .execute(company.id, requester, null)
            .shouldBeInstanceOf<CompanyResult.Unconfirmed>()
            .outcome
            .shouldBeInstanceOf<ConfirmationResult.Required>()

    private fun contactsOf(
        company: Company,
        count: Int,
    ): List<ContactId> =
        List(count) { ContactId(UUID.randomUUID()) }.sortedBy { it.value }.also { fixtures.contacts[company.id] = it }

    private fun ref(type: String) = EntityRef(type, UUID.randomUUID().toString())

    private fun refs(
        type: String,
        count: Int,
    ) = List(count) { ref(type) }.sortedBy { it.id }

    private fun contactChanges(
        field: String,
        contacts: List<ContactId>,
    ) = listOf(FieldChange(field, contacts.map { it.value.toString() }.sorted().joinToString(","), null))

    private fun ChangelogEntry.shouldBe(
        description: String,
        changes: List<FieldChange>,
    ) {
        change.description shouldBe description
        change.fieldChanges shouldContainExactly changes
    }

    @Test
    fun `one entry per application and interview names every deleted contact linked to it once`() {
        val company = fixtures.company("ACME GmbH")
        val (first, second, third) = contactsOf(company, 3)
        val (shared, onlySecond) = refs("application", 2)
        val (interview, other) = refs("interview", 2)
        fixtures.linkedApplications[first] = listOf(shared)
        fixtures.linkedApplications[second] = listOf(shared, onlySecond)
        fixtures.participations[first] = listOf(interview)
        fixtures.participations[third] = listOf(interview, other)

        delete.execute(company.id, user, firstStep(company).token) shouldBe CompanyResult.Success(Unit)

        val cascaded = fixtures.entries.drop(4)
        cascaded.map { it.entity } shouldContainExactlyInAnyOrder listOf(shared, onlySecond, interview, other)
        val byEntity = cascaded.associateBy { it.entity }
        byEntity
            .getValue(
                shared,
            ).shouldBe("Unlinked a deleted contact", contactChanges("contacts", listOf(first, second)))
        byEntity.getValue(onlySecond).shouldBe("Unlinked a deleted contact", contactChanges("contacts", listOf(second)))
        val removed = "Removed a deleted contact from the participants"
        byEntity.getValue(interview).shouldBe(removed, contactChanges("participants", listOf(first, third)))
        byEntity.getValue(other).shouldBe(removed, contactChanges("participants", listOf(third)))
    }

    @Test
    fun `the entries carry the deleting actor and the stored time, and the effect does not count them`() {
        val company = fixtures.company()
        val contact = contactsOf(company, 1).single()
        fixtures.linkedApplications[contact] = listOf(ref("application"))
        fixtures.participations[contact] = listOf(ref("interview"))
        val client = ConfirmationRequester(Actor.ExternalClient("claude-desktop"), "mcp-1")

        val required = firstStep(company, client)
        delete.execute(company.id, client, required.token) shouldBe CompanyResult.Success(Unit)

        required.action.effect.counts shouldBe mapOf("contacts" to 1, "tasks" to 0)
        fixtures.entries.size shouldBe 4
        fixtures.entries.map { it.actor }.toSet() shouldBe setOf(Actor.ExternalClient("claude-desktop"))
        fixtures.entries.map { it.occurredAt }.toSet() shouldBe setOf(NOW)
    }

    @Test
    fun `contacts without links write no entries beyond their own`() {
        val company = fixtures.company()
        val contacts = contactsOf(company, 2)

        delete.execute(company.id, user, firstStep(company).token) shouldBe CompanyResult.Success(Unit)

        fixtures.entries.map { it.entity } shouldContainExactly
            listOf(company.id.toEntityRef()) + contacts.map(ContactId::toEntityRef)
    }

    @Test
    fun `a link added between the steps is recorded without voiding the token`() {
        val company = fixtures.company()
        val contact = contactsOf(company, 1).single()
        val token = firstStep(company).token
        val application = ref("application")
        fixtures.linkedApplications[contact] = listOf(application)

        delete.execute(company.id, user, token) shouldBe CompanyResult.Success(Unit)

        fixtures.entries.map { it.entity } shouldContainExactly
            listOf(company.id.toEntityRef(), contact.toEntityRef(), application)
    }

    @Test
    fun `unreadable application links delete nothing`() {
        val company = fixtures.company()
        val contact = contactsOf(company, 1).single()
        fixtures.linkedApplications[contact] = listOf(ref("application"))
        fixtures.linkedApplicationsAvailable = false

        delete.execute(company.id, user, ConfirmationToken("any")) shouldBe
            CompanyResult.StorageFailure("read linked applications")

        fixtures.companies.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a refused application or interview entry rolls the whole delete back`() {
        val company = fixtures.company()
        val contact = contactsOf(company, 1).single()
        fixtures.linkedApplications[contact] = listOf(ref("application"))
        fixtures.participations[contact] = listOf(ref("interview"))

        for (type in listOf("application", "interview")) {
            fixtures.failingChangelogFor = type
            delete.execute(company.id, user, firstStep(company).token) shouldBe
                CompanyResult.StorageFailure("changelog")
        }

        fixtures.companies.size shouldBe 1
        fixtures.contacts.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }
}
