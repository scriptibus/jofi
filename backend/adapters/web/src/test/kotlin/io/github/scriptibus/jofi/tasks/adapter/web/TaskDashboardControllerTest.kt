// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.tasks.application.GetTaskDashboardUseCase
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
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
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * `GET /api/dashboard/tasks` (#113, ADR-0052) over the real use case with a mocked repository, at Wednesday
 * 30 September 2026, 10:00 UTC: the seven days end with Tuesday 6 October on the calendar of the zone the request
 * names. Security is tested in bootstrap.
 */
@WebMvcTest(TaskDashboardController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(TaskDashboardControllerTest.UseCases::class)
class TaskDashboardControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val tasks: TaskRepositoryPort,
) {
    private val yesterday = task(1, TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2026, 9, 29)))

    /** 22:30 UTC on Tuesday 6 October: the seventh day in UTC, already Wednesday in Berlin. */
    private val lateTuesday =
        task(2, TaskTiming.Exact(Instant.parse("2026-10-06T22:30:00Z"), ZoneId.of("Europe/Berlin")))

    private val someday = task(3, TaskTiming.Bucket.SOMEDAY)

    @BeforeEach
    fun storeThree() {
        clearMocks(tasks)
        every { tasks.listByState(TaskState.OPEN) } returns
            TaskStoreResult.Success(listOf(someday, lateTuesday, yesterday))
    }

    @Test
    fun `lists the overdue and the upcoming tasks on the calendar of the zone the request names`() {
        mvc
            .get()
            .uri("/api/dashboard/tasks?timeZone=UTC")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"overdue":[{"id":"${yesterday.id.value}","title":"Task 1","status":"OPEN",
                             "timing":{"span":"DAY","startsOn":"2026-09-29"}}],
                 "upcoming":[{"id":"${lateTuesday.id.value}",
                              "timing":{"dueAt":"2026-10-06T22:30:00Z","localDue":"2026-10-07T00:30:00"}}]}
                """.trimIndent(),
            )
        mvc
            .get()
            .uri("/api/dashboard/tasks?timeZone=Europe/Berlin")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"overdue":[{"id":"${yesterday.id.value}"}],"upcoming":[]}""")
    }

    @Test
    fun `an unknown zone is a 400 naming the query parameter, an unreadable store a 503`() {
        mvc
            .get()
            .uri("/api/dashboard/tasks?timeZone=Mars/Olympus")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${TaskProblems.INVALID}","violations":[{"field":"timeZone","problem":"INVALID_TIME_ZONE"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { tasks.listByState(any()) }

        every { tasks.listByState(any()) } returns TaskStoreResult.StorageFailure("listByState")
        mvc
            .get()
            .uri("/api/dashboard/tasks?timeZone=UTC")
            .assertThat()
            .hasStatus(503)
    }

    private fun task(
        number: Int,
        timing: TaskTiming,
    ): Task =
        Task.create(
            TaskId(UUID.fromString("00000000-0000-0000-0000-00000000000$number")),
            TaskDetails("Task $number", timing),
            TaskOrigin.Manual,
            Instant.parse("2026-09-01T08:00:00Z"),
        )

    @TestConfiguration
    class UseCases {
        @Bean
        fun tasks() = mockk<TaskRepositoryPort>()

        @Bean
        fun dashboard(tasks: TaskRepositoryPort) =
            GetTaskDashboardUseCase(tasks, Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC))
    }
}
