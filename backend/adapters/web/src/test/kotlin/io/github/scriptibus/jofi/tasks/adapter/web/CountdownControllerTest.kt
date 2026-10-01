// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.tasks.application.CreateCountdownUseCase
import io.github.scriptibus.jofi.tasks.application.DeleteCountdownUseCase
import io.github.scriptibus.jofi.tasks.application.ListCountdownsUseCase
import io.github.scriptibus.jofi.tasks.application.ListDashboardCountdownsUseCase
import io.github.scriptibus.jofi.tasks.application.UpdateCountdownUseCase
import io.github.scriptibus.jofi.tasks.application.port.CountdownRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownDetails
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The countdown endpoints over the real use cases (#112) with mocked ports: mapping, problem details, the two-step
 * delete and the dashboard query in the viewer's zone. Security (session, CSRF) is the filter chain's job, tested in
 * bootstrap.
 */
@WebMvcTest(CountdownController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(CountdownControllerTest.UseCases::class)
class CountdownControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    /** The mocked ports behind the real use cases. */
    class Ports {
        val countdowns = mockk<CountdownRepositoryPort>()
        val facts = mockk<FindCountdownFactsPort>()
        val changelog = mockk<ChangelogPort>()
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
        @Bean
        fun ports() = Ports()

        @Bean
        fun create(ports: Ports) = CreateCountdownUseCase(ports.countdowns, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun update(ports: Ports) = UpdateCountdownUseCase(ports.countdowns, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun list(ports: Ports) = ListCountdownsUseCase(ports.countdowns)

        @Bean
        fun dashboard(ports: Ports) = ListDashboardCountdownsUseCase(ports.countdowns, ports.facts, CLOCK)

        @Bean
        fun delete(ports: Ports) =
            DeleteCountdownUseCase(
                ports.countdowns,
                ConfirmActionUseCase(MapStore(), CLOCK, Duration.ofMinutes(5)),
                ports.changelog,
                ports.transactions,
                CLOCK,
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
    private val stored =
        Countdown.create(
            CountdownId(UUID.fromString("00000000-0000-0000-0000-000000000021")),
            CountdownDetails("Notice ends", LocalDate.parse("2026-12-31")),
            Instant.parse("2026-09-01T08:00:00Z"),
        )
    private val path = "/api/countdowns/${stored.id.value}"
    private val request = """{"title":"Probation ends","targetDate":"2027-01-31"}"""

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.countdowns, ports.facts, ports.changelog)
        every { ports.countdowns.findById(any()) } returns TaskStoreResult.NotFound
        every { ports.countdowns.findById(stored.id) } returns TaskStoreResult.Success(stored)
        every { ports.countdowns.add(any()) } returns TaskStoreResult.Success(Unit)
        every { ports.countdowns.update(any()) } returns TaskStoreResult.Success(Unit)
        every { ports.countdowns.delete(any(), any()) } returns TaskStoreResult.Success(Unit)
        every { ports.countdowns.list() } returns TaskStoreResult.Success(listOf(stored))
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    @Test
    fun `creating answers 201 with the new countdown, recorded as the user`() {
        json(mvc.post().uri("/api/countdowns"), request)
            .assertThat()
            .hasStatus(201)
            .bodyJson()
            .isLenientlyEqualTo("""{"title":"Probation ends","targetDate":"2027-01-31","version":0}""")
        verify { ports.countdowns.add(match { it.details.title == "Probation ends" }) }
        verify { ports.changelog.append(match { it.actor == Actor.User && it.entity.type == "countdown" }) }
    }

    @Test
    fun `invalid input is a 400 naming the request fields`() {
        json(mvc.post().uri("/api/countdowns"), """{"title":" ","targetDate":"2100-01-01"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${TaskProblems.INVALID}","violations":[
                  {"field":"title","problem":"REQUIRED"},{"field":"targetDate","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.countdowns.add(any()) }
    }

    @Test
    fun `listing answers the custom countdowns`() {
        mvc
            .get()
            .uri("/api/countdowns")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """{"countdowns":[{"id":"${stored.id.value}","title":"Notice ends","targetDate":"2026-12-31"}]}""",
            )
    }

    @Test
    fun `updating replaces title and date, a stale version is a 409 and an unknown id a 404`() {
        json(mvc.put().uri(path), """{"details":$request,"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"title":"Probation ends","targetDate":"2027-01-31","version":1}""")

        json(mvc.put().uri(path), """{"details":$request,"basedOnVersion":3}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.VERSION_CONFLICT)

        json(mvc.put().uri("/api/countdowns/${UUID.randomUUID()}"), """{"details":$request,"basedOnVersion":0}""")
            .assertThat()
            .hasStatus(404)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.COUNTDOWN_NOT_FOUND)
    }

    @Test
    fun `deleting takes two steps and the effect names the countdown`() {
        val session = MockHttpSession()

        val first = deleteStored(session)
        first.response.status shouldBe 428
        val problem = json.readTree(first.response.contentAsString)
        problem["type"].asString() shouldBe Confirmations.REQUIRED
        problem["effect"]["kind"].asString() shouldBe "countdown"
        problem["effect"]["name"].asString() shouldBe "Notice ends"
        verify(exactly = 0) { ports.countdowns.delete(any(), any()) }

        val token = problem["confirmationToken"].asString()
        deleteStored(session, token).response.status shouldBe 204
        verify { ports.countdowns.delete(stored.id, any()) }
        deleteStored(session, token).response.status shouldBe 412
    }

    @Test
    fun `the dashboard lists every countdown soonest first on the calendar of the zone the request names`() {
        val interview = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val application = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        val startsAt = Instant.parse("2026-10-06T08:00:00Z")
        // 00:30 on Thursday in Berlin, still Wednesday in UTC: the facts start from the viewer's today.
        every { ports.facts.execute(CLOCK.instant(), LocalDate.parse("2026-10-01")) } returns
            FindCountdownFactsPort.Facts.Found(
                FindCountdownFactsPort.NextInterview(interview, application, "Engineer", startsAt, TOKYO),
                listOf(FindCountdownFactsPort.DueDate(application, "Engineer", LocalDate.parse("2026-10-05"))),
                emptyList(),
            )

        val berlin =
            mvc
                .get()
                .uri("/api/dashboard/countdowns?timeZone=Europe/Berlin")
                .assertThat()
                .hasStatusOk()
                .bodyJson()
        berlin.isLenientlyEqualTo(
            """
            {"countdowns":[
              {"source":"APPLICATION_DEADLINE","title":"Engineer","targetDate":"2026-10-05",
               "subjectType":"application","subjectId":"$application"},
              {"source":"NEXT_INTERVIEW","title":"Engineer","targetAt":"2026-10-06T08:00:00Z",
               "localTarget":"2026-10-06T17:00:00","timeZone":"Asia/Tokyo",
               "subjectType":"interview","subjectId":"$interview","applicationId":"$application"},
              {"source":"CUSTOM","title":"Notice ends","targetDate":"2026-12-31",
               "subjectType":"countdown","subjectId":"${stored.id.value}"}]}
            """.trimIndent(),
        )
    }

    @Test
    fun `an unknown zone is a 400 naming the query parameter, unavailable facts a 503`() {
        mvc
            .get()
            .uri("/api/dashboard/countdowns?timeZone=Mars/Olympus")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${TaskProblems.INVALID}","violations":[{"field":"timeZone","problem":"INVALID_TIME_ZONE"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.facts.execute(any(), any()) }

        every { ports.facts.execute(any(), any()) } returns FindCountdownFactsPort.Facts.Unavailable
        mvc
            .get()
            .uri("/api/dashboard/countdowns?timeZone=UTC")
            .assertThat()
            .hasStatus(503)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.UNAVAILABLE)
    }

    @Test
    fun `requests that break the contract are rejected`() {
        badRequest(json(mvc.post().uri("/api/countdowns"), """{"title":"x","targetDate":"soon"}"""))
        badRequest(json(mvc.post().uri("/api/countdowns"), """{"targetDate":"2026-12-31"}"""))
        badRequest(json(mvc.put().uri(path), """{"details":$request}"""))
        badRequest(mvc.get().uri("/api/dashboard/countdowns"))
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

    private companion object {
        /** Wednesday 30 September 2026, 22:30 UTC: already Thursday in Berlin. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-30T22:30:00Z"), ZoneOffset.UTC)
        val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
