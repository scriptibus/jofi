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
import io.github.scriptibus.jofi.companies.domain.ChannelInput
import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyField
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.CompanyView
import io.github.scriptibus.jofi.companies.domain.CompanyViolation
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactField
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactInput
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.companies.domain.ContactViolation
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.companies.domain.ViolationKind
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The company and contact tools over mocked use cases: arguments in, the use case's input, results out. */
class CompanyContactToolsTest {
    private val companyId = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val contactId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val company = Company.create(CompanyId(companyId), CompanyDetails("ACME GmbH"), at)
    private val contact = Contact.create(ContactId(contactId), ContactDetails("Erika", role = "Recruiter"), at)

    @Test
    fun `create_company translates the arguments, acts as the caller and marks the facts untrusted`() {
        val createCompany = mockk<CreateCompanyUseCase>()
        val input = slot<CompanyInput>()
        val actor = slot<Actor>()
        every { createCompany.execute(capture(input), capture(actor)) } returns
            CompanyResult.Success(CompanyView(company, 2))

        val answer =
            CreateCompanyTool(createCompany).call(
                call(
                    "name" to "ACME GmbH",
                    "size" to "SMALL",
                    "locations" to listOf("Berlin"),
                    "website" to "https://acme.example",
                ),
            )

        input.captured shouldBe
            CompanyInput("ACME GmbH", "https://acme.example", size = CompanySize.SMALL, locations = listOf("Berlin"))
        actor.captured shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<CompanyDetailResult>()
        result.applicationCount shouldBe 2
        result.company shouldBe Untrusted(CompanyFacts("ACME GmbH", null, null, null, emptyList(), null))
    }

    @Test
    fun `a company without a name reaches the use case as an empty one, which it reports`() {
        val createCompany = mockk<CreateCompanyUseCase>()
        val input = slot<CompanyInput>()
        every { createCompany.execute(capture(input), any()) } returns
            CompanyResult.Invalid(listOf(CompanyViolation(CompanyField.NAME, ViolationKind.REQUIRED)))

        val answer = CreateCompanyTool(createCompany).call(call())

        input.captured.name shouldBe ""
        answer shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The company arguments are invalid.",
                listOf(ArgumentProblem("name", "required")),
            )
    }

    @Test
    fun `update_company passes the id and the version and maps a conflict`() {
        val updateCompany = mockk<UpdateCompanyUseCase>()
        every { updateCompany.execute(CompanyId(companyId), any(), 4L, Actor.Ai) } returns CompanyResult.VersionConflict

        val answer =
            UpdateCompanyTool(updateCompany).call(
                call(
                    "id" to companyId.toString(),
                    "version" to 4,
                    "name" to "X",
                ),
            )

        answer.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "version-conflict"
    }

    @Test
    fun `update_company needs its id and version`() {
        val tool = UpdateCompanyTool(mockk())

        shouldThrow<InvalidToolArgument> { tool.call(call("version" to 1, "name" to "X")) }.argument shouldBe "id"
        shouldThrow<InvalidToolArgument> {
            tool.call(
                call("id" to companyId.toString(), "name" to "X"),
            )
        }.argument shouldBe
            "version"
    }

    @Test
    fun `get_company and search_companies answer not-found and unavailable without stored content`() {
        val getCompany = mockk<GetCompanyUseCase>()
        every { getCompany.execute(any()) } returns CompanyResult.NotFound
        GetCompanyTool(getCompany).call(call("id" to companyId.toString())) shouldBe
            ToolAnswer.Error("not-found", "No company has this id.")

        val searchCompanies = mockk<SearchCompaniesUseCase>()
        every { searchCompanies.execute(any()) } returns CompanyResult.StorageFailure("search")
        SearchCompaniesTool(searchCompanies).call(call()).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe
            "unavailable"
    }

    @Test
    fun `search_companies translates the filters and pages with the tool's page size`() {
        val searchCompanies = mockk<SearchCompaniesUseCase>()
        val search = slot<CompanySearch>()
        every { searchCompanies.execute(capture(search)) } returns
            CompanyResult.Success(CompanyPage(listOf(CompanyView(company, 0)), 5))

        val answer = SearchCompaniesTool(searchCompanies).call(call("text" to " ACME ", "preference" to "FAVOURITE"))

        search.captured shouldBe CompanySearch("ACME", PreferenceKind.FAVOURITE, 0, 20)
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<CompanySearchResult>()
        result.total shouldBe 5
        result.companies.single().id shouldBe companyId
    }

    @Test
    fun `search_companies and search_contacts refuse an out-of-range page without searching`() {
        val companies = SearchCompaniesTool(mockk()).call(call("page" to -1, "size" to 201))
        val contacts = SearchContactsTool(mockk()).call(call("size" to 0))

        companies.shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            listOf(ArgumentProblem("page", "out-of-range"), ArgumentProblem("size", "out-of-range"))
        contacts.shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            listOf(ArgumentProblem("size", "out-of-range"))
    }

    @Test
    fun `create_contact translates the channels and names the position of a bad one`() {
        val createContact = mockk<CreateContactUseCase>()
        val input = slot<ContactInput>()
        every { createContact.execute(capture(input), Actor.Ai) } returns
            ContactResult.Invalid(listOf(ContactViolation(ContactField.CHANNEL_VALUE, ViolationKind.INVALID_EMAIL, 1)))

        val answer =
            CreateContactTool(createContact).call(
                call(
                    "name" to "Erika",
                    "companyId" to companyId.toString(),
                    "channels" to
                        listOf(
                            mapOf("kind" to "PHONE", "value" to "0170 1", "label" to "work"),
                            mapOf("kind" to "EMAIL", "value" to "nope"),
                        ),
                ),
            )

        input.captured shouldBe
            ContactInput(
                "Erika",
                company = CompanyId(companyId),
                channels =
                    listOf(
                        ChannelInput(ChannelKind.PHONE, "0170 1", "work"),
                        ChannelInput(ChannelKind.EMAIL, "nope"),
                    ),
            )
        answer.shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            listOf(ArgumentProblem("channels[1].value", "invalid-email"))
    }

    @Test
    fun `a channel without a kind or a value is an invalid argument, not a silent drop`() {
        val tool = CreateContactTool(mockk())

        shouldThrow<InvalidToolArgument> { tool.call(call("name" to "E", "channels" to listOf(mapOf("value" to "x")))) }
            .argument shouldBe "channels"
        shouldThrow<InvalidToolArgument> {
            tool.call(
                call("name" to "E", "channels" to listOf(mapOf("kind" to "EMAIL"))),
            )
        }.argument shouldBe "channels"
        shouldThrow<InvalidToolArgument> { tool.call(call("name" to "E", "channels" to listOf("x"))) }.argument shouldBe
            "channels"
    }

    @Test
    fun `a contact that names a missing company is reported on companyId`() {
        val createContact = mockk<CreateContactUseCase>()
        every { createContact.execute(any(), any()) } returns
            ContactResult.Invalid(listOf(ContactViolation(ContactField.COMPANY, ViolationKind.NOT_FOUND)))

        CreateContactTool(createContact)
            .call(call("name" to "E", "companyId" to companyId.toString()))
            .shouldBeInstanceOf<ToolAnswer.Error>()
            .problems shouldBe listOf(ArgumentProblem("companyId", "not-found"))
    }

    @Test
    fun `get_contact returns name, role and channels as untrusted and the notes plain`() {
        val getContact = mockk<GetContactUseCase>()
        every { getContact.execute(ContactId(contactId)) } returns ContactResult.Success(contact)

        val answer = GetContactTool(getContact).call(call("id" to contactId.toString()))

        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<ContactDetailResult>()
        result.contact shouldBe Untrusted(ContactFacts("Erika", "Recruiter", emptyList()))
        result.version shouldBe 0
    }

    @Test
    fun `update_contact passes the version and maps not-found, conflict and storage failures`() {
        val updateContact = mockk<UpdateContactUseCase>()
        val tool = UpdateContactTool(updateContact)
        val arguments = arrayOf("id" to contactId.toString(), "version" to 2, "name" to "E")

        every { updateContact.execute(ContactId(contactId), any(), 2L, Actor.Ai) } returns ContactResult.NotFound
        tool.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "not-found"
        every { updateContact.execute(any(), any(), any(), any()) } returns ContactResult.VersionConflict
        tool.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "version-conflict"
        every { updateContact.execute(any(), any(), any(), any()) } returns ContactResult.StorageFailure("update")
        tool.call(call(*arguments)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "unavailable"
    }

    @Test
    fun `search_contacts translates the company filter`() {
        val searchContacts = mockk<SearchContactsUseCase>()
        val search = slot<ContactSearch>()
        every { searchContacts.execute(capture(search)) } returns ContactResult.Success(CompanyPage(listOf(contact), 1))

        val answer =
            SearchContactsTool(
                searchContacts,
            ).call(call("text" to "Erika", "companyId" to companyId.toString()))

        search.captured shouldBe ContactSearch("Erika", CompanyId(companyId), 0, 20)
        answer
            .shouldBeInstanceOf<ToolAnswer.Result>()
            .value
            .shouldBeInstanceOf<ContactSearchResult>()
            .contacts
            .single()
            .id shouldBe
            contactId
    }

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)
}
