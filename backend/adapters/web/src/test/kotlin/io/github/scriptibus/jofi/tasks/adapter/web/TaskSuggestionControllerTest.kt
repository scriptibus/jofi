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
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
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
 * The suggestion endpoints over the real use cases with a mocked repository: listing and dismissing (#85); accepting
 * (#95) still answers `501`. Security is the filter chain's job, tested in bootstrap.
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
    fun `accepting a suggestion is not implemented yet`() {
        notImplemented(json(mvc.post().uri("$path/accept"), """{"basedOnVersion":0}"""))
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

    private fun notImplemented(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(501).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }
}
