// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
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

/** Paging and note excerpts of the grouped task list (#236, ADR-0056), over the real use cases. */
@WebMvcTest(TaskController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(TaskControllerTest.UseCases::class)
class TaskListPagingControllerTest(
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

    @BeforeEach
    fun reset() {
        clearMocks(ports.tasks, ports.changelog)
    }

    @Test
    fun `the grouped list is paged, shows notes as an excerpt and the single task has the whole text`() {
        val long = "n".repeat(TextExcerpt.MAX_LENGTH + 10)
        val tasks =
            (1..3).map {
                stored.copy(id = TaskId(UUID.randomUUID()), details = stored.details.copy(notes = long))
            }
        every { ports.tasks.listByState(TaskState.OPEN) } returns TaskStoreResult.Success(tasks)

        val second =
            mvc
                .get()
                .uri("/api/tasks?timeZone=UTC&page=1&size=2")
                .assertThat()
                .hasStatus(200)
                .bodyJson()

        second.extractingPath("page").isEqualTo(mapOf("page" to 1, "size" to 2, "total" to 3, "hasMore" to false))
        second.extractingPath("groups[6].tasks").asArray().hasSize(1)
        second.extractingPath("groups[6].tasks[0].notesExcerpt").isEqualTo("n".repeat(TextExcerpt.MAX_LENGTH))
        second.extractingPath("groups[6].tasks[0].notesTruncated").isEqualTo(true)
        second.extractingPath("groups[6].tasks[0]").asMap().doesNotContainKey("notes")
        every { ports.tasks.findById(tasks[0].id) } returns TaskStoreResult.Success(tasks[0])
        mvc
            .get()
            .uri("/api/tasks/${tasks[0].id.value}")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .extractingPath("notes")
            .isEqualTo(long)
    }

    @Test
    fun `a page or size out of range for the grouped list is a 400 naming it, reading nothing`() {
        mvc
            .get()
            .uri("/api/tasks?timeZone=UTC&page=0&size=0")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${TaskProblems.INVALID}","violations":[{"field":"size","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.tasks.listByState(any()) }
    }
}
