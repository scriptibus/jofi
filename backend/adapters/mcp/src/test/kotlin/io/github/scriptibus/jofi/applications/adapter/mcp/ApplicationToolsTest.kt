// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.GetApplicationUseCase
import io.github.scriptibus.jofi.applications.application.SearchApplicationsUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationOrder
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationSortKey
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.LanguageTag
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.applications.domain.TimeRange
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
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The application tools over the real use cases with a mocked repository: arguments in, results out. */
class ApplicationToolsTest {
    private val applications = mockk<ApplicationRepositoryPort>()
    private val search = SearchApplicationsTool(SearchApplicationsUseCase(applications))
    private val get = GetApplicationTool(GetApplicationUseCase(applications))

    private val company = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val contact = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val application =
        Application.create(
            ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
            ApplicationDetails("Backend Engineer", CompanyRef(company), location = "Berlin"),
            Instant.parse("2026-09-30T08:00:00Z"),
            unread = true,
        )

    @Test
    fun `search translates every argument into the use case's search`() {
        val searched = slot<ApplicationSearch>()
        every { applications.search(capture(searched)) } returns
            ApplicationStoreResult.Success(ApplicationPage(listOf(application), total = 7))

        val answer = search.call(call(*everySearchArgument()))

        searched.captured shouldBe everySearchFilter()
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<ApplicationSearchResult>()
        result.total shouldBe 7
        result.applications.single().posting shouldBe Untrusted(PostingSummary("Backend Engineer", "Berlin"))
    }

    @Test
    fun `search without arguments lists the first page in the tool's page size`() {
        val searched = slot<ApplicationSearch>()
        every { applications.search(capture(searched)) } returns
            ApplicationStoreResult.Success(ApplicationPage(emptyList(), total = 0))

        search.call(call())

        searched.captured shouldBe ApplicationSearch(size = 20)
    }

    @Test
    fun `invalid search arguments name the argument and change nothing`() {
        val answer = search.call(call("createdFrom" to "2026-10-01T00:00:00Z", "createdTo" to "2026-09-01T00:00:00Z"))

        answer shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The search arguments are invalid.",
                listOf(ArgumentProblem("createdTo", "out-of-range")),
            )
        verify(exactly = 0) { applications.search(any()) }
    }

    @Test
    fun `get returns the application with the posting's fields marked untrusted`() {
        every { applications.findById(application.id) } returns ApplicationStoreResult.Success(application)

        val answer = get.call(call("id" to application.id.value.toString()))

        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<ApplicationDetailResult>()
        result.id shouldBe application.id.value
        result.companyId shouldBe company
        result.unread shouldBe true
        result.posting shouldBe Untrusted(PostingDetails("Backend Engineer", "Berlin", emptyList()))
    }

    @Test
    fun `get answers not-found and unavailable as tool errors`() {
        val id = UUID.randomUUID().toString()
        every { applications.findById(any()) } returns ApplicationStoreResult.NotFound
        get.call(call("id" to id)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "not-found"

        every { applications.findById(any()) } returns ApplicationStoreResult.StorageFailure("find")
        get.call(call("id" to id)).shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "unavailable"
    }

    @Test
    fun `get needs an id`() {
        shouldThrow<InvalidToolArgument> { get.call(call()) }.argument shouldBe "id"
    }

    private fun everySearchArgument(): Array<Pair<String, Any?>> =
        arrayOf(
            "text" to " Backend ",
            "companyId" to company.toString(),
            "contactId" to contact.toString(),
            "statuses" to listOf("APPLIED", "OFFER"),
            "unread" to true,
            "languages" to listOf("DE-ch"),
            "sourceKinds" to listOf("URL"),
            "createdFrom" to "2026-09-01T00:00:00Z",
            "updatedTo" to "2026-10-01T00:00:00Z",
            "sort" to "DEADLINE",
            "direction" to "DESCENDING",
            "page" to 2,
            "size" to 10,
        )

    private fun everySearchFilter() =
        ApplicationSearch(
            text = "Backend",
            company = CompanyRef(company),
            contact = ContactRef(contact),
            statuses = setOf(ApplicationStatus.APPLIED, ApplicationStatus.OFFER),
            unread = true,
            languages = setOf(LanguageTag("de-CH")),
            sourceKinds = setOf(SourceKind.URL),
            created = TimeRange(Instant.parse("2026-09-01T00:00:00Z"), null),
            updated = TimeRange(null, Instant.parse("2026-10-01T00:00:00Z")),
            order = ApplicationOrder(ApplicationSortKey.DEADLINE, SortDirection.DESCENDING),
            page = 2,
            size = 10,
        )

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)
}
