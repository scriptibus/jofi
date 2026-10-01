// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.ChangeApplicationStatusUseCase
import io.github.scriptibus.jofi.applications.application.CreateApplicationUseCase
import io.github.scriptibus.jofi.applications.application.DeleteApplicationUseCase
import io.github.scriptibus.jofi.applications.application.GetApplicationStatusHistoryUseCase
import io.github.scriptibus.jofi.applications.application.GetApplicationUseCase
import io.github.scriptibus.jofi.applications.application.LinkApplicationContactsUseCase
import io.github.scriptibus.jofi.applications.application.SetApplicationUnreadUseCase
import io.github.scriptibus.jofi.applications.application.UpdateApplicationUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.spi.LinkedTasksPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.StatusChange
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
 * The application endpoints over the real use cases (#82) with mocked repositories: mapping, problem
 * details and the two-step delete; the list (#83) is `ApplicationListControllerTest`, the status endpoints (#84)
 * `ApplicationStatusControllerTest`, the contact links (#90) `ApplicationContactsControllerTest`.
 * Security (session, CSRF) is the filter chain's job, tested in bootstrap.
 */
@WebMvcTest(ApplicationController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ApplicationControllerTest.UseCases::class)
class ApplicationControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    /** The mocked ports behind the real use cases (shared with `ApplicationStatusControllerTest`). */
    class Ports {
        val applications = mockk<ApplicationRepositoryPort>()
        val snapshots = mockk<DescriptionSnapshotRepositoryPort>()
        val sources = mockk<ApplicationSourceRepositoryPort>()
        val tasks = mockk<LinkedTasksPort>()
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
        fun create(ports: Ports) =
            CreateApplicationUseCase(ports.applications, ports.changelog, ports.transactions, clock)

        @Bean
        fun get(ports: Ports) = GetApplicationUseCase(ports.applications)

        @Bean
        fun update(ports: Ports) =
            UpdateApplicationUseCase(ports.applications, ports.changelog, ports.transactions, clock)

        @Bean
        fun unread(ports: Ports) =
            SetApplicationUnreadUseCase(ports.applications, ports.changelog, ports.transactions, clock)

        @Bean
        fun linkContacts(ports: Ports) =
            LinkApplicationContactsUseCase(ports.applications, ports.changelog, ports.transactions, clock)

        @Bean
        fun delete(ports: Ports) =
            DeleteApplicationUseCase(
                ports.applications,
                ports.tasks,
                ConfirmActionUseCase(MapStore(), clock, Duration.ofMinutes(5)),
                ports.events,
                ports.changelog,
                ports.transactions,
                clock,
            )

        @Bean
        fun changeStatus(ports: Ports) =
            ChangeApplicationStatusUseCase(
                ports.applications,
                ports.snapshots,
                ports.events,
                ports.changelog,
                ports.transactions,
                clock,
            )

        @Bean
        fun statusHistory(ports: Ports) = GetApplicationStatusHistoryUseCase(ports.applications)
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
    private val companyId = "00000000-0000-0000-0000-00000000000c"
    private val contactId = "00000000-0000-0000-0000-0000000000c1"
    private val stored =
        Application
            .create(
                ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
                ApplicationDetails("Backend Engineer", CompanyRef(UUID.fromString(companyId))),
                Instant.parse("2026-09-30T08:00:00Z"),
                unread = true,
            ).copy(contacts = setOf(ContactRef(UUID.fromString(contactId))))
    private val path = "/api/applications/${stored.id.value}"
    private val details =
        """
        {"title":"Backend Engineer","companyId":"$companyId","location":"Berlin","remoteShare":60,
         "employmentType":"FULL_TIME","seniority":"SENIOR","deadline":"2026-10-31","howApplied":"PORTAL",
         "portalNotes":"Account: me@example.org",
         "payBand":{"min":70000,"max":85000.50,"currency":"EUR","period":"YEAR","source":"ESTIMATED",
                    "estimateBasis":"Similar roles in Berlin","estimateConfidence":"MEDIUM"},
         "languageAndTone":{"postingLanguage":"de","applicationLanguage":"en","formOfAddress":"DU","tone":"PERSONAL"},
         "offer":{"salary":{"amount":80000,"currency":"EUR","period":"YEAR"},"vacationDays":30,"startDate":"2027-01-01"}}
        """.trimIndent()

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.applications, ports.snapshots, ports.tasks, ports.changelog, ports.events)
        every { ports.applications.findById(any()) } returns ApplicationStoreResult.NotFound
        every { ports.applications.findById(stored.id) } returns ApplicationStoreResult.Success(stored)
        every { ports.applications.add(any(), any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.applications.updateDetails(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.applications.setUnread(any(), any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.applications.statusHistory(stored.id) } returns
            ApplicationStoreResult.Success(listOf(StatusChange.initial(stored, Actor.User)))
        every { ports.applications.snapshotCount(stored.id) } returns ApplicationStoreResult.Success(2)
        every { ports.applications.interviewCount(stored.id) } returns ApplicationStoreResult.Success(3)
        every { ports.tasks.linkedTo(stored.id.value) } returns
            LinkedTasksPort.Linked.Found(listOf(EntityRef("task", UUID.randomUUID().toString())))
        every { ports.applications.delete(any(), any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
        every { ports.events.publish(any()) } returns true
    }

    @Test
    fun `creating answers 201 with the new application, recorded as the user`() {
        val added = slot<Application>()
        every { ports.applications.add(capture(added), any()) } returns ApplicationStoreResult.Success(Unit)

        json(mvc.post().uri("/api/applications"), details)
            .assertThat()
            .hasStatus(201)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"title":"Backend Engineer","companyId":"$companyId","status":"DISCOVERED","unread":false,
                 "payBand":{"min":70000.00,"max":85000.50,"currency":"EUR","source":"ESTIMATED"},
                 "contactIds":[],"version":0}
                """.trimIndent(),
            )
        added.captured.details.title shouldBe "Backend Engineer"
        verify { ports.changelog.append(match { it.actor == Actor.User }) }
    }

    @Test
    fun `invalid details and an unknown company are a 400 naming the request fields`() {
        json(mvc.post().uri("/api/applications"), """{"title":" ","companyId":"$companyId","remoteShare":101}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${ApplicationProblems.INVALID}","violations":[
                  {"field":"remoteShare","problem":"OUT_OF_RANGE"},{"field":"title","problem":"REQUIRED"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.applications.add(any(), any()) }

        every { ports.applications.add(any(), any()) } returns ApplicationStoreResult.CompanyNotFound
        json(mvc.post().uri("/api/applications"), """{"title":"X","companyId":"${UUID.randomUUID()}"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo("""{"violations":[{"field":"companyId","problem":"NOT_FOUND"}]}""")
    }

    @Test
    fun `reading answers the application or a 404`() {
        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"id":"${stored.id.value}","unread":true,"contactIds":["$contactId"],"version":0}""")
        mvc
            .get()
            .uri("/api/applications/${UUID.randomUUID()}")
            .assertThat()
            .hasStatus(404)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.NOT_FOUND)
    }

    @Test
    fun `updating replaces the details, a stale version is a 409`() {
        json(
            mvc.put().uri(path),
            """{"details":{"title":"Staff Engineer","companyId":"$companyId"},"basedOnVersion":0}""",
        ).assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"title":"Staff Engineer","unread":true,"contactIds":["$contactId"],"version":1}""")
        verify { ports.applications.updateDetails(match { it.details.title == "Staff Engineer" }) }

        json(mvc.put().uri(path), """{"details":$details,"basedOnVersion":3}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.VERSION_CONFLICT)
    }

    @Test
    fun `marking read keeps the version`() {
        json(mvc.put().uri("$path/unread"), """{"unread":false}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"unread":false,"version":0}""")
        verify { ports.applications.setUnread(stored.id, false) }
    }

    @Test
    fun `deleting takes two steps and the effect counts what goes with the application`() {
        val session = MockHttpSession()

        val first = deleteStored(session)
        first.response.status shouldBe 428
        val problem = json.readTree(first.response.contentAsString)
        problem["type"].asString() shouldBe Confirmations.REQUIRED
        problem["effect"].toString() shouldBe
            """{"kind":"application","name":"Backend Engineer","counts":""" +
            """{"contactLinks":1,"interviews":3,"snapshots":2,"sources":0,"statusChanges":1,"tasks":1}}"""
        verify(exactly = 0) { ports.applications.delete(any(), any()) }

        val token = problem["confirmationToken"].asString()
        deleteStored(session, token).response.status shouldBe 204
        verify { ports.applications.delete(stored.id, any()) }
        verify { ports.events.publish(any()) }
        deleteStored(session, token).response.status shouldBe 412
    }

    @Test
    fun `a store that cannot answer is a 503 without details`() {
        every { ports.applications.findById(stored.id) } returns ApplicationStoreResult.StorageFailure("findById")

        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatus(503)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.UNAVAILABLE)
    }

    @Test
    fun `requests that break the contract are rejected`() {
        badRequest(mvc.get().uri("/api/applications/not-a-uuid"))
        badRequest(json(mvc.post().uri("/api/applications"), """{"title":"Backend Engineer"}"""))
        badRequest(
            json(mvc.post().uri("/api/applications"), """{"title":"X","companyId":"$companyId","seniority":"GURU"}"""),
        )
        badRequest(json(mvc.put().uri(path), """{"details":$details}"""))
        badRequest(json(mvc.put().uri("$path/unread"), """{}"""))
        badRequest(json(mvc.put().uri("$path/status"), """{"status":"APPLIED"}"""))
        badRequest(json(mvc.put().uri("$path/status"), """{"status":"HIRED","basedOnVersion":1}"""))
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    private fun deleteStored(
        session: MockHttpSession,
        token: String? = null,
    ) = mvc
        .delete()
        .uri(path)
        .session(session)
        .apply { if (token != null) header(Confirmations.HEADER, token) }
        .exchange()

    private fun json(
        request: MockMvcTester.MockMvcRequestBuilder,
        body: String,
    ): MockMvcTester.MockMvcRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(body)

    private fun badRequest(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }
}
