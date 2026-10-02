// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.InvalidParameterAdvice
import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
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
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.util.UUID

/**
 * The done tasks endpoint over the real use case with a mocked repository (#235): the page, its bounds and the
 * problem details. Security is the filter chain's job, tested in bootstrap.
 */
@WebMvcTest(DoneTaskController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(TaskControllerTest.UseCases::class, InvalidParameterAdvice::class)
class DoneTaskControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: TaskControllerTest.Ports,
) {
    @BeforeEach
    fun reset() {
        clearMocks(ports.tasks, ports.changelog)
    }

    @Test
    fun `answers the requested page of done tasks with the total, as the use case read it`() {
        val older = done("Older", Instant.parse("2026-09-01T09:00:00Z"))
        val newer = done("Newer", Instant.parse("2026-09-02T09:00:00Z"))
        every { ports.tasks.listDone(PageRequest(1, 2)) } returns
            TaskStoreResult.Success(Paged(listOf(newer, older), PageInfo(1, 2, 5, true)))

        mvc
            .get()
            .uri("/api/tasks/done?page=1&size=2")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"page":{"page":1,"size":2,"total":5,"hasMore":true},
                 "tasks":[{"id":"${newer.id.value}","title":"Newer","status":"DONE","version":1,
                           "completedAt":"2026-09-02T09:00:00Z"},
                          {"id":"${older.id.value}","title":"Older","status":"DONE","version":1,
                           "completedAt":"2026-09-01T09:00:00Z"}]}
                """.trimIndent(),
            )
    }

    @Test
    fun `without parameters it is the first page of the default size`() {
        every { ports.tasks.listDone(PageRequest.FIRST) } returns
            TaskStoreResult.Success(Paged(emptyList(), PageInfo(0, PageRequest.DEFAULT_SIZE, 0, false)))

        mvc
            .get()
            .uri("/api/tasks/done")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """{"page":{"page":0,"size":${PageRequest.DEFAULT_SIZE},"total":0,"hasMore":false},"tasks":[]}""",
            )
    }

    @Test
    fun `a page or size out of range is a 400 naming it, and nothing is read`() {
        listOf("page=-1", "size=0", "size=${PageRequest.MAX_SIZE + 1}").forEach { query ->
            mvc
                .get()
                .uri("/api/tasks/done?$query")
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .extractingPath("type")
                .isEqualTo(TaskProblems.INVALID)
        }
        mvc
            .get()
            .uri("/api/tasks/done?page=-1&size=0")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"violations":[{"field":"page","problem":"OUT_OF_RANGE"},
                               {"field":"size","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.tasks.listDone(any()) }
    }

    @Test
    fun `a page or size that is no number is the documented 400 naming it, not the framework's`() {
        mvc
            .get()
            .uri("/api/tasks/done?page=abc&size=20")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """{"violations":[{"field":"page","problem":"INVALID"}]}""",
            )
        mvc
            .get()
            .uri("/api/tasks/done?size=99999999999")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo("""{"violations":[{"field":"size","problem":"INVALID"}]}""")
        verify(exactly = 0) { ports.tasks.listDone(any()) }
    }

    @Test
    fun `an unavailable store is a 503`() {
        every { ports.tasks.listDone(any()) } returns TaskStoreResult.StorageFailure("listDone")

        mvc
            .get()
            .uri("/api/tasks/done")
            .assertThat()
            .hasStatus(503)
    }

    private fun done(
        title: String,
        completedAt: Instant,
    ): Task {
        val open =
            Task.create(
                TaskId(UUID.randomUUID()),
                TaskDetails(title, TaskTiming.Bucket.SOMEDAY),
                TaskOrigin.Manual,
                Instant.parse("2026-08-01T08:00:00Z"),
            )
        return (open.apply(TaskTransition.COMPLETE, completedAt) as TaskStateChange.Changed).task
    }
}
