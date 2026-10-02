// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.CreateCompanyUseCase
import io.github.scriptibus.jofi.companies.application.CreateContactUseCase
import io.github.scriptibus.jofi.companies.application.GetCompanyUseCase
import io.github.scriptibus.jofi.companies.application.GetContactUseCase
import io.github.scriptibus.jofi.companies.application.SearchCompaniesUseCase
import io.github.scriptibus.jofi.companies.application.SearchContactsUseCase
import io.github.scriptibus.jofi.companies.application.UpdateCompanyUseCase
import io.github.scriptibus.jofi.companies.application.UpdateContactUseCase
import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.companies.domain.ContactStoreResult
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolTestPorts
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The company and contact tools over the real use cases with mocked repositories: arguments in, results out. */
class CompanyContactToolsTest {
    private val companies = mockk<CompanyRepositoryPort>()
    private val contacts = mockk<ContactRepositoryPort>()
    private val counts = mockk<ApplicationCountsPort>()
    private val changelog = ToolTestPorts.RecordingChangelog()
    private val transactions = ToolTestPorts.transactions
    private val clock = ToolTestPorts.clock

    private val companyId = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val contactId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val company = Company.create(CompanyId(companyId), CompanyDetails("ACME GmbH"), at)
    private val contact = Contact.create(ContactId(contactId), ContactDetails("Erika", role = "Recruiter"), at)

    private val createCompany = CreateCompanyTool(CreateCompanyUseCase(companies, changelog, transactions, clock))
    private val updateCompany =
        UpdateCompanyTool(UpdateCompanyUseCase(companies, counts, changelog, transactions, clock))
    private val createContact = CreateContactTool(CreateContactUseCase(contacts, changelog, transactions, clock))
    private val updateContact = UpdateContactTool(UpdateContactUseCase(contacts, changelog, transactions, clock))

    init {
        every { counts.countByCompany(any()) } returns ApplicationCountsPort.Counts.Counted(mapOf(companyId to 2))
    }

    @Test
    fun `create_company stores the validated details, records the caller as actor and marks the facts untrusted`() {
        val added = slot<Company>()
        every { companies.add(capture(added)) } returns CompanyStoreResult.Success(Unit)

        val answer =
            createCompany.call(
                call("name" to " ACME GmbH ", "size" to "SMALL", "locations" to listOf("Berlin"), "website" to W),
            )

        added.captured.details.name shouldBe "ACME GmbH"
        added.captured.details.website
            ?.value shouldBe W
        added.captured.details.locations shouldBe listOf("Berlin")
        changelog.entries.single().actor shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<CompanyDetailResult>()
        result.version shouldBe 0
        result.company
            .shouldBeInstanceOf<Untrusted<CompanyDetailFacts>>()
            .content.name shouldBe "ACME GmbH"
    }

    @Test
    fun `a company with a missing name or a bad address names the arguments and stores nothing`() {
        val answer = createCompany.call(call("website" to "ftp://x"))

        answer shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The company arguments are invalid.",
                listOf(ArgumentProblem("website", "invalid-url"), ArgumentProblem("name", "required")),
            )
        verify(exactly = 0) { companies.add(any()) }
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `update_company is based on the given version and answers conflict and not-found without content`() {
        every { companies.findById(CompanyId(companyId)) } returns CompanyStoreResult.Success(company)
        every { companies.findById(CompanyId(MISSING)) } returns CompanyStoreResult.NotFound

        updateCompany.call(call("id" to companyId.toString(), "version" to 4, "company" to named("X"))) shouldBe
            ToolAnswer.Error(
                "version-conflict",
                "The entity changed since it was read. Read it again and retry with its current version.",
            )
        updateCompany.call(call("id" to MISSING.toString(), "version" to 0, "company" to named("X"))) shouldBe
            ToolAnswer.Error("not-found", "No company has this id.")
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `update_company replaces the details and records the caller as actor`() {
        val updated = slot<Company>()
        every { companies.findById(CompanyId(companyId)) } returns CompanyStoreResult.Success(company)
        every { companies.update(capture(updated)) } returns CompanyStoreResult.Success(Unit)

        val answer =
            updateCompany.call(
                call("id" to companyId.toString(), "version" to 0, "company" to named("ACME SE")),
            )

        updated.captured.details.name shouldBe "ACME SE"
        updated.captured.version shouldBe 1
        changelog.entries.single().actor shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<CompanyDetailResult>()
        result.readOnly.applicationCount shouldBe 2
    }

    @Test
    fun `update tools need their id and version`() {
        val noId = call("version" to 1, "company" to named("X"))
        shouldThrow<InvalidToolArgument> { updateCompany.call(noId) }.argument shouldBe "id"
        shouldThrow<InvalidToolArgument> { updateContact.call(call("id" to "$contactId", "contact" to named("X"))) }
            .argument shouldBe "version"
    }

    @Test
    fun `get_company and search_companies read through the use cases`() {
        every { companies.findById(CompanyId(MISSING)) } returns CompanyStoreResult.NotFound
        val get = GetCompanyTool(GetCompanyUseCase(companies, counts))
        get.call(call("id" to MISSING.toString())) shouldBe ToolAnswer.Error("not-found", "No company has this id.")

        val search = slot<CompanySearch>()
        every { companies.search(capture(search)) } returns CompanyStoreResult.Success(CompanyPage(listOf(company), 5))
        val answer =
            SearchCompaniesTool(SearchCompaniesUseCase(companies, counts))
                .call(call("text" to " ACME ", "preference" to "FAVOURITE"))

        search.captured shouldBe CompanySearch("ACME", PreferenceKind.FAVOURITE, 0, 20)
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<CompanySearchResult>()
        result.total shouldBe 5
        result.companies.single().applicationCount shouldBe 2
    }

    @Test
    fun `searches refuse an out-of-range page without searching`() {
        val companySearch = SearchCompaniesTool(SearchCompaniesUseCase(companies, counts))
        val contactSearch = SearchContactsTool(SearchContactsUseCase(contacts))

        companySearch.call(call("page" to -1, "size" to 51)).shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            listOf(ArgumentProblem("page", "out-of-range"), ArgumentProblem("size", "out-of-range"))
        contactSearch.call(call("size" to 0)).shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            listOf(ArgumentProblem("size", "out-of-range"))
    }

    @Test
    fun `create_contact stores the channels in order and records the caller as actor`() {
        val added = slot<Contact>()
        every { contacts.add(capture(added)) } returns ContactStoreResult.Success(Unit)

        val answer =
            createContact.call(
                call(
                    "name" to "Erika",
                    "companyId" to companyId.toString(),
                    "channels" to
                        listOf(
                            mapOf("kind" to "PHONE", "value" to "0170 1", "label" to "work"),
                            mapOf("kind" to "EMAIL", "value" to "erika@acme.example"),
                        ),
                ),
            )

        added.captured.details.company shouldBe CompanyId(companyId)
        val stored =
            added.captured.details.channels
                .map { it.kind.name to it.label }
        stored shouldBe listOf("PHONE" to "work", "EMAIL" to null)
        changelog.entries.single().actor shouldBe Actor.Ai
        answer
            .shouldBeInstanceOf<ToolAnswer.Result>()
            .value
            .shouldBeInstanceOf<ContactDetailResult>()
            .contact.content.channels.size shouldBe 2
    }

    @Test
    fun `a bad channel is named by its position and a missing company on companyId`() {
        val bad = listOf(mapOf("kind" to "PHONE", "value" to "0170 1"), mapOf("kind" to "EMAIL", "value" to "nope"))
        createContact
            .call(
                call("name" to "E", "channels" to bad),
            ).shouldBeInstanceOf<ToolAnswer.Error>()
            .problems shouldBe
            listOf(ArgumentProblem("channels[1].value", "invalid-email"))

        every { contacts.add(any()) } returns ContactStoreResult.CompanyNotFound
        createContact
            .call(call("name" to "E", "companyId" to companyId.toString()))
            .shouldBeInstanceOf<ToolAnswer.Error>()
            .problems shouldBe listOf(ArgumentProblem("companyId", "not-found"))
    }

    @Test
    fun `a channel without a kind or a value is an invalid argument, not a silent drop`() {
        val noKind = call("name" to "E", "channels" to listOf(mapOf("value" to "x")))
        val noValue = call("name" to "E", "channels" to listOf(mapOf("kind" to "EMAIL")))
        shouldThrow<InvalidToolArgument> { createContact.call(noKind) }.argument shouldBe "channels"
        shouldThrow<InvalidToolArgument> { createContact.call(noValue) }.argument shouldBe "channels"
        shouldThrow<InvalidToolArgument> { createContact.call(call("name" to "E", "channels" to listOf("x"))) }
            .argument shouldBe "channels"
    }

    @Test
    fun `get_contact returns name, role and channels as untrusted and the notes plain`() {
        every { contacts.findById(ContactId(contactId)) } returns ContactStoreResult.Success(contact)

        val answer = GetContactTool(GetContactUseCase(contacts)).call(call("id" to contactId.toString()))

        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<ContactDetailResult>()
        result.contact shouldBe Untrusted(ContactDetailFacts("Erika", "Recruiter", emptyList(), null))
        result.version shouldBe 0
    }

    @Test
    fun `update_contact is based on the version and maps not-found, conflict and storage failures`() {
        val arguments = arrayOf("id" to contactId.toString(), "version" to 2, "contact" to named("E"))

        every { contacts.findById(any()) } returns ContactStoreResult.NotFound
        updateContact.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "not-found"
        every { contacts.findById(any()) } returns ContactStoreResult.Success(contact)
        updateContact.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "version-conflict"
        every { contacts.findById(any()) } returns ContactStoreResult.StorageFailure("find")
        updateContact.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "unavailable"
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `search_contacts translates the company filter`() {
        val search = slot<ContactSearch>()
        every { contacts.search(capture(search)) } returns ContactStoreResult.Success(CompanyPage(listOf(contact), 1))

        val answer =
            SearchContactsTool(SearchContactsUseCase(contacts))
                .call(call("text" to "Erika", "companyId" to companyId.toString()))

        search.captured shouldBe ContactSearch("Erika", CompanyId(companyId), 0, 20)
        answer
            .shouldBeInstanceOf<ToolAnswer.Result>()
            .value
            .shouldBeInstanceOf<ContactSearchResult>()
            .contacts
            .single()
            .id shouldBe contactId
    }

    /** The `company` or `contact` object of an update: only the name matters to these tests. */
    private fun named(name: String) = mapOf("name" to name)

    @Test
    fun `the create tools of companies and contacts refuse the withheld marker and store nothing`() {
        createCompany
            .call(call("name" to "ACME", "locations" to listOf("Berlin", "[withheld]")))
            .shouldBeInstanceOf<ToolAnswer.Error>()
            .problems shouldBe listOf(ArgumentProblem("locations[1]", "withheld-value"))
        val channels = listOf(mapOf("kind" to "EMAIL", "value" to "x", "label" to "a [withheld]"))
        createContact
            .call(call("name" to "E", "channels" to channels))
            .shouldBeInstanceOf<ToolAnswer.Error>()
            .problems shouldBe listOf(ArgumentProblem("channels[0].label", "withheld-value"))
        verify(exactly = 0) { companies.add(any()) }
        verify(exactly = 0) { contacts.add(any()) }
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `an update without its company or contact object is an invalid argument`() {
        shouldThrow<InvalidToolArgument> { updateCompany.call(call("id" to "$companyId", "version" to 0)) }
            .argument shouldBe "company"
        shouldThrow<InvalidToolArgument> { updateContact.call(call("id" to "$contactId", "version" to 0)) }
            .argument shouldBe "contact"
    }

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)

    private companion object {
        val MISSING: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
        const val W = "https://acme.example"
    }
}
