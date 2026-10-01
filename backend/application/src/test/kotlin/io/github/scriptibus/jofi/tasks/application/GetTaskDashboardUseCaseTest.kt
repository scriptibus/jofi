// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDashboard
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The dashboard's tasks at the fixtures' clock: Wednesday 30 September 2026, 23:30 in Berlin, Thursday in Tokyo. */
class GetTaskDashboardUseCaseTest {
    private val fixtures = TaskFixtures()
    private val useCase = GetTaskDashboardUseCase(fixtures.repository, TaskFixtures.CLOCK)

    @Test
    fun `splits the open tasks into overdue and upcoming on the calendar of the viewer's zone`() {
        val wednesday = fixtures.task(timing = day(2026, 9, 30))
        val nextWednesday = fixtures.task(timing = day(2026, 10, 7))
        fixtures.task()

        dashboardIn("Europe/Berlin") shouldBe TaskDashboard(emptyList(), listOf(wednesday))
        dashboardIn("Asia/Tokyo") shouldBe TaskDashboard(listOf(wednesday), listOf(nextWednesday))
    }

    @Test
    fun `leaves out done tasks and suggestions`() {
        val done =
            fixtures
                .task(timing = day(2026, 9, 30))
                .apply(TaskTransition.COMPLETE, TaskFixtures.NOW)
                .shouldBeInstanceOf<TaskStateChange.Changed>()
                .task
        fixtures.tasks[done.id] = done
        val suggested =
            Task.suggest(
                TaskId(UUID.randomUUID()),
                done.details,
                TaskOrigin.Suggested("follow-up", "application:1"),
                TaskFixtures.CREATED,
            )
        fixtures.tasks[suggested.id] = suggested

        dashboardIn("UTC") shouldBe TaskDashboard(emptyList(), emptyList())
    }

    @Test
    fun `a store that cannot answer is a storage failure`() {
        fixtures.failingStore = true

        useCase.execute(ZoneId.of("UTC")) shouldBe TaskResult.StorageFailure("listByState")
    }

    private fun dashboardIn(zone: String): TaskDashboard =
        useCase.execute(ZoneId.of(zone)).shouldBeInstanceOf<TaskResult.Success<TaskDashboard>>().value

    private fun day(
        year: Int,
        month: Int,
        day: Int,
    ): TaskTiming = TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(year, month, day))
}
