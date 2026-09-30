// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanyPreference
import io.github.scriptibus.jofi.companies.domain.CompanyProfile
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.companies.domain.WebAddress
import io.github.scriptibus.jofi.setup.adapter.persistence.ConfirmedProofs
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** `CompanyRepository` on a real PostgreSQL migrated from zero: round trips, versions, search, delete. */
class CompanyRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: CompanyRepository
    private lateinit var rows: ApplicationRows

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = CompanyRepository(dsl)
        rows = ApplicationRows(dsl)
    }

    private fun stored(
        name: String,
        preference: CompanyPreference = CompanyPreference.None,
    ): Company {
        val company =
            Company
                .create(
                    CompanyId(UUID.randomUUID()),
                    CompanyDetails(name),
                    CREATED,
                ).copy(preference = preference)
        repository.add(company) shouldBe CompanyStoreResult.Success(Unit)
        return company
    }

    private fun names(search: CompanySearch): List<String> =
        repository
            .search(search)
            .shouldBeInstanceOf<CompanyStoreResult.Success<CompanyPage<Company>>>()
            .value.items
            .map { it.details.name }

    @Test
    fun `a company with every field reads back equal`() {
        val details =
            CompanyDetails(
                name = "Bücher & Söhne GmbH",
                website = WebAddress("https://bücher.example"),
                industry = "Books",
                size = CompanySize.ENTERPRISE,
                locations = listOf("İstanbul", "istanbul", "Remote"),
                careersPage = WebAddress("https://jobs.example/my_team?x=1"),
                researchNotes = "# Notes",
            )
        val company =
            Company(
                CompanyId(UUID.randomUUID()),
                details,
                CompanyProfile("Sells books.", CREATED),
                CompanyPreference.Favourite("Great team"),
                version = 4,
                createdAt = CREATED,
                updatedAt = CREATED.plusSeconds(60),
            )

        repository.add(company) shouldBe CompanyStoreResult.Success(Unit)

        repository.findById(company.id) shouldBe CompanyStoreResult.Success(company)
        repository.findById(CompanyId(UUID.randomUUID())) shouldBe CompanyStoreResult.NotFound
    }

    @Test
    fun `an update stores only on top of the version it was based on`() {
        val company = stored("ACME GmbH")
        val edited = company.edit(CompanyDetails("ACME SE"), LATER)

        repository.update(edited) shouldBe CompanyStoreResult.Success(Unit)
        repository.update(company.edit(CompanyDetails("ACME AG"), LATER)) shouldBe CompanyStoreResult.VersionConflict
        repository.update(edited) shouldBe CompanyStoreResult.VersionConflict

        repository.findById(company.id) shouldBe CompanyStoreResult.Success(edited)
        val unknown = Company.create(CompanyId(UUID.randomUUID()), CompanyDetails("Nobody"), CREATED)
        repository.update(unknown.edit(CompanyDetails("Still nobody"), LATER)) shouldBe CompanyStoreResult.NotFound
    }

    @Test
    fun `search ranks whole-word matches first and tolerates typos`() {
        listOf("Globex", "Acme Robotics Holding", "ACME GmbH", "Initech", "Akme Industries").forEach(::stored)

        names(CompanySearch(text = "acme")) shouldContainExactly listOf("ACME GmbH", "Acme Robotics Holding")
        names(CompanySearch(text = "Globx")) shouldContainExactly listOf("Globex")
        names(CompanySearch(text = "tech")) shouldContainExactly listOf("Initech")
        names(CompanySearch()) shouldContainExactly
            listOf("ACME GmbH", "Acme Robotics Holding", "Akme Industries", "Globex", "Initech")
    }

    @Test
    fun `search treats LIKE wildcards as text`() {
        listOf("My_Team", "Mytheam", "100% Solutions").forEach(::stored)

        names(CompanySearch(text = "y_T")) shouldContainExactly listOf("My_Team")
        names(CompanySearch(text = "%")) shouldContainExactly listOf("100% Solutions")
    }

    @Test
    fun `search filters by preference and pages with the total`() {
        stored("Alpha", CompanyPreference.Favourite())
        stored("Beta", CompanyPreference.Blacklisted("Rude"))
        stored("Gamma", CompanyPreference.Favourite())
        stored("Delta")

        names(CompanySearch(preference = PreferenceKind.FAVOURITE)) shouldContainExactly listOf("Alpha", "Gamma")
        val page =
            repository
                .search(CompanySearch(page = 1, size = 3))
                .shouldBeInstanceOf<CompanyStoreResult.Success<CompanyPage<Company>>>()
                .value
        page.items.map { it.details.name } shouldContainExactly listOf("Gamma")
        page.total shouldBe 4
    }

    @Test
    fun `the contact ids of a company are those deleted with it`() {
        val company = stored("ACME GmbH")
        val contacts = List(3) { rows.contact(company.id.value) }
        rows.contact(null)

        repository
            .findContactIds(company.id)
            .shouldBeInstanceOf<CompanyStoreResult.Success<List<ContactId>>>()
            .value shouldContainExactlyInAnyOrder contacts.map(::ContactId)
        repository.findContactIds(CompanyId(UUID.randomUUID())) shouldBe CompanyStoreResult.Success(emptyList())
    }

    @Test
    fun `a confirmed delete removes the company and its contacts`() {
        val company = stored("ACME GmbH")
        rows.contact(company.id.value)
        val other = stored("Globex")

        repository.delete(company.id, proofFor(company.id)) shouldBe CompanyStoreResult.Success(Unit)

        repository.findById(company.id) shouldBe CompanyStoreResult.NotFound
        dsl.fetchCount(CONTACT) shouldBe 0
        dsl.fetchCount(COMPANY) shouldBe 1
        repository.delete(company.id, proofFor(company.id)) shouldBe CompanyStoreResult.NotFound
        repository.findById(other.id).shouldBeInstanceOf<CompanyStoreResult.Success<Company>>()
    }

    @Test
    fun `a delete needs a proof for exactly this company`() {
        val company = stored("ACME GmbH")
        val other = stored("Globex")

        repository.delete(company.id, proofFor(other.id)) shouldBe CompanyStoreResult.NotConfirmed
        repository.delete(company.id, ConfirmedProofs.of("contacts.delete", company.id.value.toString())) shouldBe
            CompanyStoreResult.NotConfirmed
        dsl.fetchCount(COMPANY) shouldBe 2
    }

    @Test
    fun `a company with applications is refused by the name of its foreign key`() {
        val company = stored("ACME GmbH")
        rows.application(UUID.randomUUID(), company.id.value)

        repository.delete(company.id, proofFor(company.id)) shouldBe CompanyStoreResult.HasApplications
        repository.findById(company.id).shouldBeInstanceOf<CompanyStoreResult.Success<Company>>()
    }

    @Test
    fun `a failing statement is a storage failure, not an exception`() {
        val company = stored("ACME GmbH")

        repository.add(company) shouldBe CompanyStoreResult.StorageFailure("add")
    }

    private fun proofFor(id: CompanyId) = ConfirmedProofs.of(Company.DELETE_OPERATION, id.value.toString())

    private companion object {
        val CREATED: Instant = Instant.parse("2026-09-30T08:00:00.123456Z")
        val LATER: Instant = Instant.parse("2026-09-30T09:00:00.654321Z")
    }
}
