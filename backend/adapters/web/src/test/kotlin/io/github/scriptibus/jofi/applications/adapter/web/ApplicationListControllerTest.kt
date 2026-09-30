// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

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
import io.github.scriptibus.jofi.applications.domain.Score
import io.github.scriptibus.jofi.applications.domain.ScoreRange
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.applications.domain.TimeRange
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.util.UUID

/**
 * `GET /api/applications` (#83) over the real use case with a mocked repository: every query parameter
 * reaches the search, bad ones are a 400 naming them, a store failure is a 503.
 */
@WebMvcTest(ApplicationController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ApplicationControllerTest.UseCases::class)
class ApplicationListControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: ApplicationControllerTest.Ports,
) {
    private val company = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val contact = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val application =
        Application.create(
            ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
            ApplicationDetails("Backend Engineer", CompanyRef(company)),
            Instant.parse("2026-09-30T08:00:00Z"),
            unread = true,
        )
    private val searched = slot<ApplicationSearch>()

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.applications)
        every { ports.applications.search(capture(searched)) } returns
            ApplicationStoreResult.Success(ApplicationPage(listOf(application), total = 3))
    }

    @Test
    fun `without parameters the first page of everything is listed`() {
        mvc
            .get()
            .uri("/api/applications")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"applications":[{"id":"${application.id.value}","title":"Backend Engineer","unread":true}],
                 "page":0,"size":${ApplicationSearch.DEFAULT_SIZE},"total":3}
                """.trimIndent(),
            )
        searched.captured shouldBe ApplicationSearch()
    }

    @Test
    fun `every query parameter reaches the search`() {
        val query =
            "search= Backend &companyId=$company&contactId=$contact&status=APPLIED&status=OFFER&unread=true" +
                "&language=DE-ch&language=en&sourceKind=URL&createdFrom=2026-09-01T00:00:00Z" +
                "&createdTo=2026-10-01T00:00:00Z&updatedFrom=2026-09-15T00:00:00Z&wantMin=3.5&fitMax=4" +
                "&sort=DEADLINE&direction=DESCENDING&page=2&size=10"

        mvc
            .get()
            .uri("/api/applications?$query")
            .assertThat()
            .bodyJson()
            .isLenientlyEqualTo("""{"page":2,"size":10}""")

        searched.captured shouldBe
            ApplicationSearch(
                text = "Backend",
                company = CompanyRef(company),
                contact = ContactRef(contact),
                statuses = setOf(ApplicationStatus.APPLIED, ApplicationStatus.OFFER),
                unread = true,
                languages = setOf(LanguageTag("de-CH"), LanguageTag("en")),
                sourceKinds = setOf(SourceKind.URL),
                created = TimeRange(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")),
                updated = TimeRange(Instant.parse("2026-09-15T00:00:00Z"), null),
                wantScore = ScoreRange(Score(35), null),
                fitScore = ScoreRange(null, Score(40)),
                order = ApplicationOrder(ApplicationSortKey.DEADLINE, SortDirection.DESCENDING),
                page = 2,
                size = 10,
            )
    }

    @Test
    fun `list parameters also take comma-separated values`() {
        mvc
            .get()
            .uri("/api/applications?status=APPLIED,INTERVIEWING&sourceKind=SCANNER,URL")
            .assertThat()
            .hasStatusOk()

        searched.captured.statuses shouldBe setOf(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEWING)
        searched.captured.sourceKinds shouldBe setOf(SourceKind.SCANNER, SourceKind.URL)
    }

    @Test
    fun `invalid parameters are a 400 naming each of them, and nothing is searched`() {
        val query =
            "language=german!&createdFrom=2026-10-01T00:00:00Z&createdTo=2026-09-01T00:00:00Z" +
                "&wantMin=4.25&fitMin=6&page=-1&size=${ApplicationSearch.MAX_SIZE + 1}"

        mvc
            .get()
            .uri("/api/applications?$query")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${ApplicationProblems.INVALID_SEARCH}","violations":[
                  {"field":"language","problem":"INVALID_LANGUAGE"},{"field":"createdTo","problem":"OUT_OF_RANGE"},
                  {"field":"wantMin","problem":"TOO_PRECISE"},{"field":"fitMin","problem":"OUT_OF_RANGE"},
                  {"field":"page","problem":"OUT_OF_RANGE"},{"field":"size","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.applications.search(any()) }
    }

    @Test
    fun `values of the wrong type are a 400`() {
        for (query in listOf("status=HIRED", "sourceKind=EMAIL", "createdFrom=yesterday", "unread=maybe", "page=x")) {
            mvc
                .get()
                .uri("/api/applications?$query")
                .assertThat()
                .hasStatus(400)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        }
        verify(exactly = 0) { ports.applications.search(any()) }
    }

    @Test
    fun `a store that cannot answer is a 503`() {
        every { ports.applications.search(any()) } returns ApplicationStoreResult.StorageFailure("search")

        mvc
            .get()
            .uri("/api/applications")
            .assertThat()
            .hasStatus(503)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.UNAVAILABLE)
    }

    @Test
    fun `every API sort enum has exactly the constants of its domain enum`() {
        ApplicationListSort.entries.map { it.name } shouldBe ApplicationSortKey.entries.map { it.name }
        ApplicationListDirection.entries.map { it.name } shouldBe SortDirection.entries.map { it.name }
    }
}
