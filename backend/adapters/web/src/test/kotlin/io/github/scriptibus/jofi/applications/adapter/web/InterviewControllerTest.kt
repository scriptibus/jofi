// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.DeleteInterviewUseCase
import io.github.scriptibus.jofi.applications.application.GetInterviewUseCase
import io.github.scriptibus.jofi.applications.application.ListInterviewsUseCase
import io.github.scriptibus.jofi.applications.application.ListUpcomingInterviewsUseCase
import io.github.scriptibus.jofi.applications.application.LogInterviewUseCase
import io.github.scriptibus.jofi.applications.application.UpdateInterviewUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.Paged
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The interview endpoints over the real use cases (#91) with mocked repositories: mapping (the agreed wall-clock time
 * and zone in, the instant too out), problem details, the two-step delete and the upcoming list across applications
 * (#92).
 * Security (session, CSRF) is the filter chain's job, tested in bootstrap.
 */
@WebMvcTest(InterviewController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(InterviewControllerTest.UseCases::class)
class InterviewControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    class Ports {
        val applications = mockk<ApplicationRepositoryPort>()
        val interviews = mockk<InterviewRepositoryPort>()
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
        fun log(ports: Ports) =
            LogInterviewUseCase(
                ports.applications,
                ports.interviews,
                ports.events,
                ports.changelog,
                ports.transactions,
                clock,
            )

        @Bean
        fun update(ports: Ports) =
            UpdateInterviewUseCase(
                ports.applications,
                ports.interviews,
                ports.events,
                ports.changelog,
                ports.transactions,
                clock,
            )

        @Bean
        fun get(ports: Ports) = GetInterviewUseCase(ports.applications, ports.interviews)

        @Bean
        fun list(ports: Ports) = ListInterviewsUseCase(ports.applications, ports.interviews)

        @Bean
        fun upcoming(ports: Ports) = ListUpcomingInterviewsUseCase(ports.interviews, Clock.fixed(NOW, ZoneOffset.UTC))

        @Bean
        fun delete(ports: Ports) =
            DeleteInterviewUseCase(
                ports.applications,
                ports.interviews,
                ConfirmActionUseCase(MapStore(), clock, Duration.ofMinutes(5)),
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
    private val application =
        Application.create(
            ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
            ApplicationDetails("Backend Engineer", CompanyRef(UUID.fromString("00000000-0000-0000-0000-00000000000c"))),
            Instant.parse("2026-09-30T08:00:00Z"),
        )
    private val contactId = "00000000-0000-0000-0000-0000000000c1"
    private val stored =
        Interview
            .log(
                InterviewId(UUID.fromString("00000000-0000-0000-0000-0000000000f1")),
                application.id,
                InterviewDetails(
                    InterviewType.TECHNICAL,
                    InterviewTime(Instant.parse("2026-10-05T08:00:00Z"), ZoneId.of("Europe/Berlin")),
                    participants = setOf(ContactRef(UUID.fromString(contactId))),
                    preparationNotes = "# Prep",
                ),
                Actor.User,
                Instant.parse("2026-09-30T09:00:00Z"),
            ).interview
    private val base = "/api/applications/${application.id.value}/interviews"
    private val one = "$base/${stored.id.value}"
    private val details =
        """
        {"type":"TECHNICAL","localStart":"2026-10-05T10:00","timeZone":"Europe/Berlin",
         "participantIds":["$contactId"],"preparationNotes":"# Prep","notes":null,"outcome":null}
        """.trimIndent()

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.applications, ports.interviews, ports.changelog, ports.events)
        every { ports.applications.findById(any()) } returns ApplicationStoreResult.NotFound
        every { ports.applications.findById(application.id) } returns ApplicationStoreResult.Success(application)
        every { ports.interviews.findById(any(), any()) } returns ApplicationStoreResult.NotFound
        every { ports.interviews.findById(application.id, stored.id) } returns ApplicationStoreResult.Success(stored)
        every { ports.interviews.pageByApplication(application.id, any(), any()) } returns
            ApplicationStoreResult.Success(Paged(listOf(stored), PageInfo(0, 20, 1, false)))
        every { ports.interviews.add(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.interviews.update(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.interviews.delete(any(), any(), any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
        every { ports.events.publish(any()) } returns true
    }

    @Test
    fun `logging answers 201 with the instant resolved from the agreed time, recorded as the user`() {
        val added = slot<Interview>()
        every { ports.interviews.add(capture(added)) } returns ApplicationStoreResult.Success(Unit)

        json(
            mvc.post().uri(base),
            """{"type":"PHONE_SCREEN","localStart":"2026-10-25T02:30","timeZone":"Europe/Berlin"}""",
        ).assertThat()
            .hasStatus(201)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"applicationId":"${application.id.value}","type":"PHONE_SCREEN","startsAt":"2026-10-25T00:30:00Z",
                 "localStart":"2026-10-25T02:30:00","timeZone":"Europe/Berlin","participantIds":[],"version":0}
                """.trimIndent(),
            )
        added.captured.details.type shouldBe InterviewType.PHONE_SCREEN
        verify { ports.changelog.append(match { it.actor == Actor.User }) }
        verify { ports.events.publish(any()) }
    }

    @Test
    fun `an unknown zone and an unknown participant are a 400 naming the request fields`() {
        json(mvc.post().uri(base), """{"type":"HR","localStart":"2026-10-05T10:00","timeZone":"Mars/Olympus"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo("""{"violations":[{"field":"timeZone","problem":"INVALID_TIME_ZONE"}]}""")
        verify(exactly = 0) { ports.interviews.add(any()) }

        every { ports.interviews.add(any()) } returns ApplicationStoreResult.ContactNotFound
        json(mvc.post().uri(base), details)
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo("""{"violations":[{"field":"participantIds","problem":"NOT_FOUND"}]}""")
    }

    @Test
    fun `reading and listing answer the interviews, or tell the missing application from the missing interview`() {
        mvc
            .get()
            .uri(one)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"id":"${stored.id.value}","localStart":"2026-10-05T10:00:00","startsAt":"2026-10-05T08:00:00Z",
                 "participantIds":["$contactId"],"preparationNotes":"# Prep"}
                """.trimIndent(),
            )
        mvc
            .get()
            .uri(base)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .extractingPath("interviews[0].id")
            .isEqualTo(stored.id.value.toString())
        problem(mvc.get().uri("$base/${UUID.randomUUID()}"), 404, ApplicationProblems.INTERVIEW_NOT_FOUND)
        problem(mvc.get().uri("/api/applications/${UUID.randomUUID()}/interviews"), 404, ApplicationProblems.NOT_FOUND)
    }

    @Test
    fun `updating replaces the details, a stale version is a 409`() {
        json(mvc.put().uri(one), """{"details":${details.replace("# Prep", "# Prep 2")},"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"preparationNotes":"# Prep 2","version":1}""")
        verify { ports.interviews.update(match { it.details.preparationNotes == "# Prep 2" }) }

        problem(
            json(mvc.put().uri(one), """{"details":$details,"basedOnVersion":3}"""),
            409,
            ApplicationProblems.VERSION_CONFLICT,
        )
    }

    @Test
    fun `deleting takes two steps and the effect names the interview`() {
        val session = MockHttpSession()

        val first = deleteStored(session)
        first.response.status shouldBe 428
        val problem = json.readTree(first.response.contentAsString)
        problem["type"].asString() shouldBe Confirmations.REQUIRED
        problem["effect"].toString() shouldBe
            """{"kind":"interview","name":"TECHNICAL 2026-10-05T10:00 Europe/Berlin","counts":{}}"""
        verify(exactly = 0) { ports.interviews.delete(any(), any(), any()) }

        val token = problem["confirmationToken"].asString()
        deleteStored(session, token).response.status shouldBe 204
        verify { ports.interviews.delete(application.id, stored.id, any()) }
        deleteStored(session, token).response.status shouldBe 412
    }

    @Test
    fun `the upcoming list answers the interviews still to come from now on, with their application's title`() {
        every { ports.interviews.upcoming(NOW, Interview.MAX_UPCOMING) } returns
            ApplicationStoreResult.Success(listOf(UpcomingInterview(stored, "Backend Engineer")))

        mvc
            .get()
            .uri("/api/interviews/upcoming")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"interviews":[{"applicationTitle":"Backend Engineer",
                  "interview":{"id":"${stored.id.value}","applicationId":"${application.id.value}","type":"TECHNICAL",
                   "startsAt":"2026-10-05T08:00:00Z","localStart":"2026-10-05T10:00:00","timeZone":"Europe/Berlin"}}]}
                """.trimIndent(),
            )

        every { ports.interviews.upcoming(any(), any()) } returns ApplicationStoreResult.StorageFailure("upcoming")
        problem(mvc.get().uri("/api/interviews/upcoming"), 503, ApplicationProblems.UNAVAILABLE)
    }

    @Test
    fun `requests that break the contract are rejected`() {
        badRequest(json(mvc.post().uri(base), """{"localStart":"2026-10-05T10:00","timeZone":"UTC"}"""))
        badRequest(json(mvc.post().uri(base), """{"type":"LUNCH","localStart":"2026-10-05T10:00","timeZone":"UTC"}"""))
        badRequest(json(mvc.post().uri(base), """{"type":"HR","localStart":"next monday","timeZone":"UTC"}"""))
        badRequest(json(mvc.post().uri(base), """{"type":"HR","localStart":"2026-10-05T10:00"}"""))
        badRequest(json(mvc.put().uri(one), """{"details":$details}"""))
        badRequest(mvc.get().uri("$base/not-a-uuid"))
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    private fun deleteStored(
        session: MockHttpSession,
        token: String? = null,
    ) = mvc
        .delete()
        .uri(one)
        .session(session)
        .apply { if (token != null) header(Confirmations.HEADER, token) }
        .exchange()

    private fun json(
        request: MockMvcTester.MockMvcRequestBuilder,
        body: String,
    ): MockMvcTester.MockMvcRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(body)

    private fun problem(
        request: MockMvcTester.MockMvcRequestBuilder,
        status: Int,
        type: String,
    ) {
        request
            .assertThat()
            .hasStatus(status)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(type)
    }

    private fun badRequest(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-01T12:00:00Z")
    }
}
