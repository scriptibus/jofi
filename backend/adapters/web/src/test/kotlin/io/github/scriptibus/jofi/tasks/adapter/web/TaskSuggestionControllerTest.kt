// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.mockk.clearMocks
import io.mockk.every
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
 * The suggestion endpoints over the real use cases with a mocked repository: listing and dismissing (#85), accepting
 * (#95). Security is the filter chain's job, tested in bootstrap.
 */
@WebMvcTest(TaskSuggestionController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(TaskControllerTest.UseCases::class)
class TaskSuggestionControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: TaskControllerTest.Ports,
) {
    private val stored =
        Task.create(
            TaskId(UUID.fromString("00000000-0000-0000-0000-000000000011")),
            TaskDetails("Call back", TaskTiming.Bucket.SOMEDAY),
            TaskOrigin.Manual,
            Instant.parse("2026-09-01T08:00:00Z"),
        )
    private val path = "/api/tasks/${stored.id.value}"

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.tasks, ports.changelog)
        every { ports.tasks.findById(stored.id) } returns TaskStoreResult.Success(stored)
        every { ports.tasks.update(any()) } returns TaskStoreResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    @Test
    fun `the suggestions are listed newest first with their rule`() {
        val older = suggested("follow-up", Instant.parse("2026-09-01T08:00:00Z"))
        val newer = suggested("ghosted-suggestion", Instant.parse("2026-09-02T08:00:00Z"))
        every { ports.tasks.listByState(TaskState.SUGGESTED) } returns TaskStoreResult.Success(listOf(older, newer))

        mvc
            .get()
            .uri("/api/tasks/suggestions")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"tasks":[{"id":"${newer.id.value}","suggestionRule":"ghosted-suggestion","status":"SUGGESTED"},
                          {"id":"${older.id.value}","suggestionRule":"follow-up","status":"SUGGESTED"}]}
                """.trimIndent(),
            )
    }

    @Test
    fun `dismissing a suggestion records it as the user, but an open task cannot be dismissed`() {
        every { ports.tasks.findById(stored.id) } returns
            TaskStoreResult.Success(suggested("ghosted-suggestion", Instant.parse("2026-09-01T08:00:00Z"), stored.id))

        json(mvc.post().uri("$path/dismiss"), """{"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"DISMISSED","version":1}""")
        verify { ports.changelog.append(match { it.actor == Actor.User }) }

        every { ports.tasks.findById(stored.id) } returns TaskStoreResult.Success(stored)
        json(mvc.post().uri("$path/dismiss"), """{"basedOnVersion":0}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.INVALID_TRANSITION)
    }

    @Test
    fun `accepting a suggestion opens it as the user, a stale version is a conflict, a done task cannot be accepted`() {
        every { ports.tasks.findById(stored.id) } returns
            TaskStoreResult.Success(suggested("follow-up", Instant.parse("2026-09-01T08:00:00Z"), stored.id))

        json(mvc.post().uri("$path/accept"), """{"basedOnVersion":1}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.VERSION_CONFLICT)
        json(mvc.post().uri("$path/accept"), """{"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"OPEN","version":1,"suggestionRule":"follow-up"}""")
        verify(exactly = 1) {
            ports.changelog.append(match { it.actor == Actor.User && it.change.description == "Accepted suggestion" })
        }

        val done = (stored.apply(TaskTransition.COMPLETE, stored.createdAt) as TaskStateChange.Changed).task
        every { ports.tasks.findById(stored.id) } returns TaskStoreResult.Success(done)
        json(mvc.post().uri("$path/accept"), """{"basedOnVersion":1}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.INVALID_TRANSITION)
    }

    @Test
    fun `accepting an open task that never was a suggestion is an invalid transition, writing nothing`() {
        json(mvc.post().uri("$path/accept"), """{"basedOnVersion":0}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(TaskProblems.INVALID_TRANSITION)
        verify(exactly = 0) { ports.tasks.update(any()) }
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    @Test
    fun `accepting an unknown task is not found`() {
        every { ports.tasks.findById(stored.id) } returns TaskStoreResult.NotFound

        json(mvc.post().uri("$path/accept"), """{"basedOnVersion":0}""").assertThat().hasStatus(404)
    }

    private fun suggested(
        rule: String,
        at: Instant,
        id: TaskId = TaskId(UUID.randomUUID()),
    ): Task = Task.suggest(id, stored.details, TaskOrigin.Suggested(rule, "application:$id"), at)

    private fun json(
        request: MockMvcTester.MockMvcRequestBuilder,
        body: String,
    ): MockMvcTester.MockMvcRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(body)
}
