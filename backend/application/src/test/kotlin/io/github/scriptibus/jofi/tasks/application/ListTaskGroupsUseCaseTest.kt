// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskGroupKind
import io.github.scriptibus.jofi.tasks.domain.TaskGroupsPage
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The grouped list at the fixtures' clock: Wednesday 30 September 2026, 23:30 in Berlin, Thursday in Tokyo. */
class ListTaskGroupsUseCaseTest {
    private val fixtures = TaskFixtures()
    private val useCase = ListTaskGroupsUseCase(fixtures.repository, TaskFixtures.CLOCK, fixtures.redaction)

    @Test
    fun `groups the open tasks on the calendar of the viewer's zone`() {
        val wednesday = fixtures.task(timing = TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2026, 9, 30)))
        val someday = fixtures.task()

        groupsIn("Europe/Berlin") shouldBe expected(TaskGroupKind.TODAY to wednesday, TaskGroupKind.SOMEDAY to someday)
        groupsIn("Asia/Tokyo") shouldBe expected(TaskGroupKind.OVERDUE to wednesday, TaskGroupKind.SOMEDAY to someday)
    }

    @Test
    fun `leaves out done tasks and suggestions`() {
        val completed = fixtures.task().apply(TaskTransition.COMPLETE, TaskFixtures.NOW)
        val done = completed.shouldBeInstanceOf<TaskStateChange.Changed>().task
        fixtures.tasks[done.id] = done
        val suggestion = TaskOrigin.Suggested("follow-up", "application:1")
        val suggested = Task.suggest(TaskId(UUID.randomUUID()), done.details, suggestion, TaskFixtures.CREATED)
        fixtures.tasks[suggested.id] = suggested

        groupsIn("UTC").values.flatten() shouldBe emptyList()
        fixtures.tasks.values.map { it.state } shouldContainExactly listOf(TaskState.DONE, TaskState.SUGGESTED)
    }

    @Test
    fun `a store that cannot answer is a storage failure`() {
        fixtures.failingStore = true

        useCase.execute(ZoneId.of("UTC"), PageInput(), NotesAudience.USER) shouldBe
            TaskResult.StorageFailure("listByState")
    }

    private fun groupsIn(zone: String): Map<TaskGroupKind, List<Task>> =
        useCase
            .execute(ZoneId.of(zone), PageInput(), NotesAudience.USER)
            .shouldBeInstanceOf<TaskResult.Success<TaskGroupsPage>>()
            .value
            .groups
            .associate { group -> group.kind to group.tasks.map { fixtures.tasks.getValue(it.id) } }

    private fun expected(vararg filled: Pair<TaskGroupKind, Task>): Map<TaskGroupKind, List<Task>> =
        TaskGroupKind.entries.associateWith { emptyList<Task>() } + filled.map { (kind, task) -> kind to listOf(task) }
}
