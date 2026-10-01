// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.application.CreateCompanyUseCase
import io.github.scriptibus.jofi.companies.application.DeleteCompanyUseCase
import io.github.scriptibus.jofi.companies.application.FindCompanyLinksUseCase
import io.github.scriptibus.jofi.companies.application.GetCompanyUseCase
import io.github.scriptibus.jofi.companies.application.SearchCompaniesUseCase
import io.github.scriptibus.jofi.companies.application.SetCompanyPreferenceUseCase
import io.github.scriptibus.jofi.companies.application.UpdateCompanyUseCase
import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.application.port.spi.TaskLinksPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The company endpoints over the real use cases with mocked ports: mapping, problem details and the
 * two-step delete. Security (session, CSRF) is the filter chain's job, tested in bootstrap.
 */
@WebMvcTest(CompanyController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(CompanyControllerTest.UseCases::class)
class CompanyControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    /** The mocked ports behind the real use cases. */
    class Ports {
        val companies = mockk<CompanyRepositoryPort>()
        val applications = mockk<ApplicationCountsPort>()
        val tasks = mockk<TaskLinksPort>()
        val changelog = mockk<ChangelogPort>()
        val events = mockk<DomainEventPort>()
        val transactions =
            object : TransactionPort {
                override fun <T> inTransaction(
                    commitIf: (T) -> Boolean,
                    work: () -> T,
                ): T = work()
            }
    }

    @TestConfiguration
    class UseCases {
        private val clock = Clock.systemUTC()

        @Bean
        fun ports() = Ports()

        @Bean
        fun search(ports: Ports) = SearchCompaniesUseCase(ports.companies, ports.applications)

        @Bean
        fun create(ports: Ports) = CreateCompanyUseCase(ports.companies, ports.changelog, ports.transactions, clock)

        @Bean
        fun get(ports: Ports) = GetCompanyUseCase(ports.companies, ports.applications)

        @Bean
        fun update(ports: Ports) =
            UpdateCompanyUseCase(ports.companies, ports.applications, ports.changelog, ports.transactions, clock)

        @Bean
        fun preference(ports: Ports) =
            SetCompanyPreferenceUseCase(
                ports.companies,
                ports.applications,
                ports.events,
                ports.changelog,
                ports.transactions,
                clock,
            )

        @Bean
        fun delete(ports: Ports) =
            DeleteCompanyUseCase(
                ports.companies,
                FindCompanyLinksUseCase(ports.applications, ports.tasks),
                ConfirmActionUseCase(MapStore(), clock, Duration.ofMinutes(5)),
                ports.events,
                ports.changelog,
                ports.transactions,
                clock,
            )
    }

    private class MapStore : ConfirmationStorePort {
        private val pending = mutableMapOf<String, PendingConfirmation>()

        override fun issue(
            pending: PendingConfirmation,
            now: Instant,
        ) = ConfirmationToken(UUID.randomUUID().toString()).also { this.pending[it.value] = pending }

        override fun redeem(token: ConfirmationToken) = pending.remove(token.value)
    }

    private val json = JsonMapper.builder().build()
    private val acme =
        Company.create(
            CompanyId(UUID.fromString("00000000-0000-0000-0000-00000000000c")),
            CompanyDetails("ACME GmbH", locations = listOf("Berlin")),
            Instant.parse("2026-09-30T08:00:00Z"),
        )
    private val path = "/api/companies/${acme.id.value}"
    private val details = """{"name":"ACME GmbH","size":"MEDIUM","locations":["Berlin"]}"""

    @BeforeEach
    fun storeAcme() {
        clearMocks(ports.companies, ports.applications, ports.tasks, ports.changelog, ports.events)
        every { ports.companies.findById(any()) } returns CompanyStoreResult.NotFound
        every { ports.companies.findById(acme.id) } returns CompanyStoreResult.Success(acme)
        every { ports.companies.add(any()) } returns CompanyStoreResult.Success(Unit)
        every { ports.companies.update(any()) } returns CompanyStoreResult.Success(Unit)
        every { ports.companies.findContactIds(acme.id) } returns
            CompanyStoreResult.Success(listOf(ContactId(UUID.randomUUID()), ContactId(UUID.randomUUID())))
        every { ports.companies.delete(any(), any()) } returns CompanyStoreResult.Success(Unit)
        every { ports.applications.countByCompany(any()) } returns ApplicationCountsPort.Counts.Counted(emptyMap())
        every { ports.tasks.linkedTo(setOf(acme.id.value), any()) } returns
            TaskLinksPort.Links.Found(
                listOf(TaskLinksPort.LinkedTask(acme.id.value, EntityRef("task", UUID.randomUUID().toString()))),
                emptyList(),
            )
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
        every { ports.events.publish(any()) } returns true
    }

    @Test
    fun `searching answers a page with application counts`() {
        val search = slot<CompanySearch>()
        every { ports.companies.search(capture(search)) } returns
            CompanyStoreResult.Success(CompanyPage(listOf(acme), 21))
        every { ports.applications.countByCompany(setOf(acme.id.value)) } returns
            ApplicationCountsPort.Counts.Counted(mapOf(acme.id.value to 3))

        mvc
            .get()
            .uri("/api/companies?search= acme &preference=FAVOURITE&page=1&size=20")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"companies":[{"id":"${acme.id.value}","name":"ACME GmbH","locations":["Berlin"],
                  "preference":"NONE","version":0,"applicationCount":3}],
                 "page":1,"size":20,"total":21}
                """.trimIndent(),
            )
        search.captured shouldBe CompanySearch("acme", PreferenceKind.FAVOURITE, 1, 20)
    }

    @Test
    fun `search parameters out of range and unknown values are a 400`() {
        mvc
            .get()
            .uri("/api/companies?size=${CompanySearch.MAX_SIZE + 1}")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${CompanyProblems.INVALID_SEARCH}",
                 "violations":[{"field":"size","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
        mvc
            .get()
            .uri("/api/companies?preference=MAYBE")
            .assertThat()
            .hasStatus(400)
        mvc
            .get()
            .uri("/api/companies/not-a-uuid")
            .assertThat()
            .hasStatus(400)
    }

    @Test
    fun `creating answers 201 with the new company, recorded as the user`() {
        mvc
            .post()
            .uri("/api/companies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(details)
            .assertThat()
            .hasStatus(201)
            .bodyJson()
            .isLenientlyEqualTo("""{"name":"ACME GmbH","size":"MEDIUM","version":0,"applicationCount":0}""")
        verify { ports.changelog.append(match { it.actor == Actor.User }) }
    }

    @Test
    fun `invalid details are a 400 naming the request fields`() {
        mvc
            .post()
            .uri("/api/companies")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":" ","website":"ftp://acme.example"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${CompanyProblems.INVALID}","violations":[
                  {"field":"website","problem":"INVALID_URL"},{"field":"name","problem":"REQUIRED"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.companies.add(any()) }
    }

    @Test
    fun `reading an unknown company is a 404`() {
        mvc
            .get()
            .uri("/api/companies/${UUID.randomUUID()}")
            .assertThat()
            .hasStatus(404)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(CompanyProblems.NOT_FOUND)
        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatusOk()
    }

    @Test
    fun `updating replaces the details, a stale version is a 409`() {
        mvc
            .put()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"details":{"name":"ACME SE"},"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"name":"ACME SE","locations":[],"version":1}""")
        mvc
            .put()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"details":$details,"basedOnVersion":3}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(CompanyProblems.VERSION_CONFLICT)
    }

    @Test
    fun `setting the preference answers the flagged company and announces it`() {
        mvc
            .put()
            .uri("$path/preference")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"preference":"BLACKLISTED","reason":"Declined twice","basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"preference":"BLACKLISTED","preferenceReason":"Declined twice","version":1}""")
        verify { ports.events.publish(any()) }
        mvc
            .put()
            .uri("$path/preference")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"preference":"BLACKLISTED"}""")
            .assertThat()
            .hasStatus(400)
    }

    @Test
    fun `deleting takes two steps and the effect counts the contacts and linked tasks`() {
        val session = MockHttpSession()

        val first = deleteAcme(session)
        first.response.status shouldBe 428
        val problem = json.readTree(first.response.contentAsString)
        problem["type"].asString() shouldBe Confirmations.REQUIRED
        problem["effect"].toString() shouldBe
            """{"kind":"company","name":"ACME GmbH","counts":{"contacts":2,"tasks":1}}"""
        verify(exactly = 0) { ports.companies.delete(any(), any()) }

        val token = problem["confirmationToken"].asString()
        deleteAcme(session, token).response.status shouldBe 204
        verify { ports.companies.delete(acme.id, any()) }
        deleteAcme(session, token).response.status shouldBe 412
    }

    @Test
    fun `a company with applications cannot be deleted`() {
        every { ports.applications.countByCompany(any()) } returns
            ApplicationCountsPort.Counts.Counted(mapOf(acme.id.value to 1))

        val result = deleteAcme(MockHttpSession())

        result.response.status shouldBe 409
        json.readTree(result.response.contentAsString)["type"].asString() shouldBe CompanyProblems.HAS_APPLICATIONS
    }

    @Test
    fun `a store that cannot answer is a 503 without details`() {
        every { ports.companies.findById(acme.id) } returns CompanyStoreResult.StorageFailure("findById")

        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatus(503)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(CompanyProblems.UNAVAILABLE)
    }

    private fun deleteAcme(
        session: MockHttpSession,
        token: String? = null,
    ) = mvc
        .delete()
        .uri(path)
        .session(session)
        .apply { if (token != null) header(Confirmations.HEADER, token) }
        .exchange()
}
