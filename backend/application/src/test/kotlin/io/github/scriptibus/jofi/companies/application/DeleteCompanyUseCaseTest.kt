// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.NOW
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.ContactDeleted
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.shared.domain.Actor
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
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class DeleteCompanyUseCaseTest {
    private val fixtures = CompanyFixtures()
    private val delete =
        DeleteCompanyUseCase(
            fixtures.companyPort,
            fixtures.applicationPort,
            fixtures.confirmation,
            fixtures.eventPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val user = ConfirmationRequester(Actor.User, "session-1")

    private fun firstStep(
        id: CompanyId,
        requester: ConfirmationRequester = user,
    ): ConfirmationResult.Required {
        val result = delete.execute(id, requester, null).shouldBeInstanceOf<CompanyResult.Unconfirmed>()
        return result.outcome.shouldBeInstanceOf<ConfirmationResult.Required>()
    }

    private fun contactsOf(
        company: Company,
        count: Int,
    ): List<ContactId> =
        List(count) { ContactId(UUID.randomUUID()) }.sortedBy { it.value }.also { fixtures.contacts[company.id] = it }

    @Test
    fun `the first call only asks, counting the contacts that go with the company`() {
        val company = fixtures.company("ACME GmbH")
        contactsOf(company, 2)

        val required = firstStep(company.id)

        required.action.operation shouldBe Company.DELETE_OPERATION
        required.action.targets shouldBe listOf(company.id.value.toString())
        required.action.effect shouldBe ConfirmationEffect("company", "ACME GmbH", mapOf("contacts" to 2))
        fixtures.companies.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `the confirmed repeat deletes, records company and each contact by id and announces each contact`() {
        val company = fixtures.company("ACME GmbH")
        val contacts = contactsOf(company, 2)
        val ai = ConfirmationRequester(Actor.Ai, "chat-7")

        delete.execute(company.id, ai, firstStep(company.id, ai).token) shouldBe CompanyResult.Success(Unit)

        fixtures.companies.shouldBeEmpty()
        fixtures.entries.map { it.entity } shouldContainExactly
            listOf(company.id.toEntityRef()) + contacts.map(ContactId::toEntityRef)
        fixtures.entries.map { it.actor }.toSet() shouldBe setOf(Actor.Ai)
        fixtures.entries.map { it.occurredAt }.toSet() shouldBe setOf(NOW)
        fixtures.entries
            .first()
            .change.fieldChanges shouldContainExactly
            listOf(FieldChange("name", "ACME GmbH", null))
        fixtures.entries.drop(1).forEach { it.change.fieldChanges.shouldBeEmpty() }
        fixtures.events shouldContainExactly contacts.map { ContactDeleted(it, Actor.Ai, NOW) }
    }

    @Test
    fun `a company with applications is refused before a token is issued`() {
        val company = fixtures.company()
        fixtures.applicationCounts[company.id.value] = 1

        delete.execute(company.id, user, null) shouldBe CompanyResult.HasApplications
        fixtures.companies.size shouldBe 1
    }

    @Test
    fun `an application added between count and delete is refused by the store and rolls back`() {
        val company = fixtures.company()
        contactsOf(company, 1)
        val token = firstStep(company.id).token
        fixtures.restrictedByApplications += company.id

        delete.execute(company.id, user, token) shouldBe CompanyResult.HasApplications
        fixtures.companies.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `a contact added between the steps changes the effect and voids the token`() {
        val company = fixtures.company()
        contactsOf(company, 1)
        val token = firstStep(company.id).token
        contactsOf(company, 2)

        delete.execute(company.id, user, token) shouldBe
            CompanyResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))
        fixtures.companies.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a rename between the steps voids the token, other edits do not`() {
        val company = fixtures.company("ACME GmbH")
        val renamedToken = firstStep(company.id).token
        fixtures.companies[company.id] = company.copy(details = CompanyDetails("ACME SE"))
        delete.execute(company.id, user, renamedToken) shouldBe
            CompanyResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))

        val token = firstStep(company.id).token
        fixtures.companies[company.id] = company.copy(details = CompanyDetails("ACME SE", industry = "Robotics"))
        delete.execute(company.id, user, token) shouldBe CompanyResult.Success(Unit)
    }

    @Test
    fun `a token works once, only in its session and only for its company`() {
        val company = fixtures.company()
        val other = fixtures.company("Globex")
        val stolen = firstStep(company.id).token
        val retargeted = firstStep(company.id).token

        delete.execute(company.id, ConfirmationRequester(Actor.User, "session-2"), stolen) shouldBe
            CompanyResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))
        delete.execute(other.id, user, retargeted) shouldBe
            CompanyResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH))
        val token = firstStep(company.id).token
        delete.execute(company.id, user, token) shouldBe CompanyResult.Success(Unit)
        delete.execute(other.id, user, token) shouldBe
            CompanyResult.Unconfirmed(ConfirmationResult.Rejected(ConfirmationRejection.UNKNOWN))
        fixtures.companies.keys shouldContainExactly setOf(other.id)
    }

    @Test
    fun `unknown companies and unreadable counts delete nothing`() {
        delete.execute(CompanyId(UUID.randomUUID()), user, null) shouldBe CompanyResult.NotFound
        val company = fixtures.company()
        fixtures.countsAvailable = false

        delete.execute(company.id, user, ConfirmationToken("any")) shouldBe
            CompanyResult.StorageFailure("count applications")
        fixtures.companies.size shouldBe 1
    }

    @Test
    fun `a failing changelog or event rolls the whole delete back`() {
        val company = fixtures.company()
        contactsOf(company, 1)
        fixtures.failingChangelog = true
        delete.execute(company.id, user, firstStep(company.id).token) shouldBe CompanyResult.StorageFailure("changelog")
        fixtures.failingChangelog = false
        fixtures.failingEvents = true

        delete.execute(company.id, user, firstStep(company.id).token) shouldBe
            CompanyResult.StorageFailure("publish event")

        fixtures.companies.size shouldBe 1
        fixtures.contacts.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `without contacts the effect counts zero`() {
        val company = fixtures.company()

        firstStep(company.id).action.effect.counts shouldBe mapOf("contacts" to 0)
        fixtures.contacts.shouldBeEmpty()
    }
}
