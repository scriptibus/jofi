// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.tasks.application.AcceptTaskSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.CompleteTaskUseCase
import io.github.scriptibus.jofi.tasks.application.CreateTaskUseCase
import io.github.scriptibus.jofi.tasks.application.DeleteTaskUseCase
import io.github.scriptibus.jofi.tasks.application.DismissTaskSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.GetTaskUseCase
import io.github.scriptibus.jofi.tasks.application.ListSuggestedTasksUseCase
import io.github.scriptibus.jofi.tasks.application.ListTaskGroupsUseCase
import io.github.scriptibus.jofi.tasks.application.ReopenTaskUseCase
import io.github.scriptibus.jofi.tasks.application.UpdateTaskUseCase
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
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
 * The task endpoints over the real use cases (#93, #94) with a mocked repository: mapping, problem details, the
 * grouped list in the viewer's zone and the two-step delete; the suggestions are in [TaskSuggestionControllerTest].
 * Security (session, CSRF) is the filter chain's job, tested in bootstrap.
 */
@WebMvcTest(TaskController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(TaskControllerTest.UseCases::class)
class TaskControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    /** The mocked ports behind the real use cases. */
    class Ports {
        val tasks = mockk<TaskRepositoryPort>()
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
        fun create(ports: Ports) = CreateTaskUseCase(ports.tasks, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun get(ports: Ports) = GetTaskUseCase(ports.tasks)

        @Bean
        fun listGroups(ports: Ports) = ListTaskGroupsUseCase(ports.tasks, CLOCK)

        @Bean
        fun update(ports: Ports) = UpdateTaskUseCase(ports.tasks, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun complete(ports: Ports) = CompleteTaskUseCase(ports.tasks, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun reopen(ports: Ports) = ReopenTaskUseCase(ports.tasks, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun listSuggestions(ports: Ports) = ListSuggestedTasksUseCase(ports.tasks)

        @Bean
        fun accept(ports: Ports) = AcceptTaskSuggestionUseCase(ports.tasks, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun dismiss(ports: Ports) =
            DismissTaskSuggestionUseCase(ports.tasks, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun delete(ports: Ports) =
            DeleteTaskUseCase(
                ports.tasks,
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
    private val contactId = "00000000-0000-0000-0000-0000000000c1"
    private val stored =
        Task.create(
            TaskId(UUID.fromString("00000000-0000-0000-0000-000000000011")),
            TaskDetails("Call back", TaskTiming.Bucket.SOMEDAY, ContactRef(UUID.fromString(contactId))),
            TaskOrigin.Manual,
            Instant.parse("2026-09-01T08:00:00Z"),
        )
    private val path = "/api/tasks/${stored.id.value}"
    private val exact =
        """
        {"title":"Call back","timing":{"timeZone":"Europe/Berlin","localDue":"2026-10-05T10:00"},
         "link":{"type":"CONTACT","id":"$contactId"},"notes":"# Ask"}
        """.trimIndent()
    private val bucket = """{"title":"Research","timing":{"timeZone":"Europe/Berlin","bucket":"THIS_WEEK"}}"""

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.tasks, ports.changelog)
        every { ports.tasks.findById(any()) } returns TaskStoreResult.NotFound
        every { ports.tasks.findById(stored.id) } returns TaskStoreResult.Success(stored)
        every { ports.tasks.add(any()) } returns TaskStoreResult.Success(Unit)
        every { ports.tasks.update(any()) } returns TaskStoreResult.Success(Unit)
        every { ports.tasks.delete(any(), any()) } returns TaskStoreResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    @Test
    fun `creating answers 201 with the new task, recorded as the user`() {
        val added = slot<Task>()
        every { ports.tasks.add(capture(added)) } returns TaskStoreResult.Success(Unit)

        json(mvc.post().uri("/api/tasks"), exact)
            .assertThat()
            .hasStatus(201)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"title":"Call back","notes":"# Ask","link":{"type":"CONTACT","id":"$contactId"},
                 "timing":{"dueAt":"2026-10-05T08:00:00Z","localDue":"2026-10-05T10:00:00","timeZone":"Europe/Berlin"},
                 "origin":"MANUAL","status":"OPEN","version":0}
                """.trimIndent(),
            )
        added.captured.origin shouldBe TaskOrigin.Manual
        verify { ports.changelog.append(match { it.actor == Actor.User }) }
    }

    @Test
    fun `a bucket is the week of today in the zone the request names`() {
        json(mvc.post().uri("/api/tasks"), bucket)
            .assertThat()
            .hasStatus(201)
            .bodyJson()
            .isLenientlyEqualTo("""{"timing":{"span":"WEEK","startsOn":"2026-09-28","endsBefore":"2026-10-05"}}""")
    }

    @Test
    fun `invalid input and a link to nothing are a 400 naming the request fields`() {
        json(
            mvc.post().uri("/api/tasks"),
            """{"title":" ","timing":{"timeZone":"Mars/Olympus","bucket":"TODAY","localDue":"2026-10-05T10:00"}}""",
        ).assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${TaskProblems.INVALID}","violations":[
                  {"field":"title","problem":"REQUIRED"},{"field":"timing.timeZone","problem":"INVALID_TIME_ZONE"},
                  {"field":"timing","problem":"AMBIGUOUS"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.tasks.add(any()) }

        every { ports.tasks.add(any()) } returns TaskStoreResult.LinkNotFound
        json(mvc.post().uri("/api/tasks"), exact)
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo("""{"violations":[{"field":"link.id","problem":"NOT_FOUND"}]}""")
    }

    @Test
    fun `reading answers the task or a 404`() {
        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"id":"${stored.id.value}","timing":{"span":"SOMEDAY"},"version":0}""")
        mvc
            .get()
            .uri("/api/tasks/${UUID.randomUUID()}")
            .assertThat()
            .hasStatus(404)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.NOT_FOUND)
    }

    @Test
    fun `updating replaces the details, a stale version is a 409`() {
        json(mvc.put().uri(path), """{"details":$bucket,"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"title":"Research","timing":{"span":"WEEK"},"version":1}""")
        verify { ports.tasks.update(match { it.details.title == "Research" && it.details.link == null }) }

        json(mvc.put().uri(path), """{"details":$bucket,"basedOnVersion":3}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.VERSION_CONFLICT)
    }

    @Test
    fun `completing marks the task done, reopening an open task changes nothing`() {
        json(mvc.post().uri("$path/complete"), """{"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"DONE","completedAt":"2026-09-30T10:00:00Z","version":1}""")

        json(mvc.post().uri("$path/reopen"), """{"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"OPEN","version":0}""")
        verify(exactly = 1) { ports.tasks.update(any()) }
    }

    @Test
    fun `a transition the task does not allow is a 409`() {
        val suggestion = TaskOrigin.Suggested("follow-up", "application:1")
        val suggested = Task.suggest(stored.id, stored.details, suggestion, Instant.parse("2026-09-01T08:00:00Z"))
        every { ports.tasks.findById(stored.id) } returns TaskStoreResult.Success(suggested)

        json(mvc.post().uri("$path/complete"), """{"basedOnVersion":0}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.INVALID_TRANSITION)
    }

    @Test
    fun `deleting takes two steps and the effect names the task`() {
        val session = MockHttpSession()

        val first = deleteStored(session)
        first.response.status shouldBe 428
        val problem = json.readTree(first.response.contentAsString)
        problem["type"].asString() shouldBe Confirmations.REQUIRED
        problem["effect"]["kind"].asString() shouldBe "task"
        problem["effect"]["name"].asString() shouldBe "Call back"
        verify(exactly = 0) { ports.tasks.delete(any(), any()) }

        val token = problem["confirmationToken"].asString()
        deleteStored(session, token).response.status shouldBe 204
        verify { ports.tasks.delete(stored.id, any()) }
        deleteStored(session, token).response.status shouldBe 412
    }

    @Test
    fun `a store that cannot answer is a 503 without details`() {
        every { ports.tasks.findById(stored.id) } returns TaskStoreResult.StorageFailure("findById")

        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatus(503)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.UNAVAILABLE)
    }

    @Test
    fun `the grouped list shows the open tasks on the calendar of the zone the request names`() {
        // 00:30 on Thursday in Berlin, still Wednesday (today) in UTC.
        val afterMidnightInBerlin =
            TaskTiming.Exact(Instant.parse("2026-09-30T22:30:00Z"), ZoneId.of("Europe/Berlin"))
        val task = stored.copy(details = stored.details.copy(timing = afterMidnightInBerlin))
        every { ports.tasks.listByState(TaskState.OPEN) } returns TaskStoreResult.Success(listOf(task))
        val groups =
            """[{"group":"OVERDUE"},{"group":"TODAY"},{"group":"THIS_WEEK"},{"group":"NEXT_WEEK"},""" +
                """{"group":"THIS_MONTH"},{"group":"LATER"},{"group":"SOMEDAY"}]"""

        val utc =
            mvc
                .get()
                .uri("/api/tasks?timeZone=UTC")
                .assertThat()
                .hasStatus(200)
                .bodyJson()
        utc.isLenientlyEqualTo("""{"groups":$groups}""")
        utc.extractingPath("groups[1].tasks[0].id").isEqualTo(task.id.value.toString())
        utc.extractingPath("groups[2].tasks").asArray().isEmpty()
        val berlin =
            mvc
                .get()
                .uri("/api/tasks?timeZone=Europe/Berlin")
                .assertThat()
                .hasStatus(200)
                .bodyJson()
        berlin.extractingPath("groups[1].tasks").asArray().isEmpty()
        berlin.extractingPath("groups[2].tasks[0].timing.localDue").isEqualTo("2026-10-01T00:30:00")
    }

    @Test
    fun `an unknown zone for the grouped list is a 400 naming the query parameter`() {
        mvc
            .get()
            .uri("/api/tasks?timeZone=Mars/Olympus")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${TaskProblems.INVALID}","violations":[{"field":"timeZone","problem":"INVALID_TIME_ZONE"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.tasks.listByState(any()) }

        every { ports.tasks.listByState(any()) } returns TaskStoreResult.StorageFailure("listByState")
        mvc
            .get()
            .uri("/api/tasks?timeZone=UTC")
            .assertThat()
            .hasStatus(503)
    }

    @Test
    fun `requests that break the contract are rejected`() {
        badRequest(json(mvc.post().uri("/api/tasks"), """{"timing":{"timeZone":"UTC","bucket":"TODAY"}}"""))
        badRequest(json(mvc.post().uri("/api/tasks"), """{"title":"x","timing":{"timeZone":"UTC","bucket":"SOON"}}"""))
        badRequest(json(mvc.post().uri("/api/tasks"), """{"title":"x","timing":{"bucket":"TODAY"}}"""))
        badRequest(
            json(
                mvc.post().uri("/api/tasks"),
                """{"title":"x","timing":{"timeZone":"UTC"},"link":{"type":"JOB","id":"1"}}""",
            ),
        )
        badRequest(json(mvc.put().uri(path), """{"details":$bucket}"""))
        badRequest(json(mvc.post().uri("$path/complete"), "{}"))
        badRequest(mvc.get().uri("/api/tasks"))
        badRequest(mvc.get().uri("/api/tasks/not-a-uuid"))
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
        /** Wednesday 30 September 2026, noon in Berlin. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC)
    }
}
