// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.NOW
import io.github.scriptibus.jofi.tasks.domain.DoneTaskPage
import io.github.scriptibus.jofi.tasks.domain.DoneTaskQuery
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskGroup
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The done tasks (#235): the list, and the way back through reopen, on the fixtures' clock. */
class ListDoneTasksUseCaseTest {
    private val fixtures = TaskFixtures()
    private val listDone = ListDoneTasksUseCase(fixtures.repository)
    private val listOpen = ListTaskGroupsUseCase(fixtures.repository, CLOCK)
    private val complete = CompleteTaskUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val reopen = ReopenTaskUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)

    private fun doneAt(completedAt: Instant): Task {
        val open = fixtures.task()
        val done = open.apply(TaskTransition.COMPLETE, completedAt).shouldBeInstanceOf<TaskStateChange.Changed>().task
        fixtures.tasks[done.id] = done
        return done
    }

    private fun page(
        page: Int = 0,
        size: Int = DoneTaskQuery.DEFAULT_SIZE,
    ): DoneTaskPage =
        listDone.execute(DoneTaskQuery(page, size)).shouldBeInstanceOf<TaskResult.Success<DoneTaskPage>>().value

    private fun openTasks(): List<Task> =
        listOpen
            .execute(ZoneOffset.UTC)
            .shouldBeInstanceOf<TaskResult.Success<List<TaskGroup>>>()
            .value
            .flatMap { it.tasks }

    @Test
    fun `lists the done tasks newest completion first, one bounded page at a time`() {
        val first = doneAt(NOW.plusSeconds(10))
        val second = doneAt(NOW.plusSeconds(20))
        val third = doneAt(NOW.plusSeconds(30))

        page() shouldBe DoneTaskPage(listOf(third, second, first), 3)
        page(0, 2) shouldBe DoneTaskPage(listOf(third, second), 3)
        page(1, 2) shouldBe DoneTaskPage(listOf(first), 3)
        page(2, 2) shouldBe DoneTaskPage(emptyList(), 3)
    }

    @Test
    fun `leaves out open tasks and suggestions`() {
        fixtures.task()
        val suggested =
            Task.suggest(
                TaskId(UUID.randomUUID()),
                fixtures.task().details,
                TaskOrigin.Suggested("follow-up", "application:1"),
                NOW,
            )
        fixtures.tasks[suggested.id] = suggested

        page() shouldBe DoneTaskPage(emptyList(), 0)
        fixtures.tasks.values.count { it.state == TaskState.DONE } shouldBe 0
    }

    @Test
    fun `a task completed by the AI is found under done and reopened, each recorded with its actor`() {
        val task = fixtures.task()
        complete.execute(task.id, 0, Actor.Ai)
        openTasks().shouldBeEmpty()

        val found = page().tasks.single()
        found.id shouldBe task.id
        found.completedAt shouldBe NOW
        reopen.execute(found.id, found.version, Actor.User).shouldBeInstanceOf<TaskResult.Success<Task>>()

        page() shouldBe DoneTaskPage(emptyList(), 0)
        openTasks().map { it.id } shouldBe listOf(task.id)
        fixtures.tasks.getValue(task.id).state shouldBe TaskState.OPEN
        fixtures.entries.map { it.change.description to it.actor } shouldBe
            listOf("Completed task" to Actor.Ai, "Reopened task" to Actor.User)
    }

    @Test
    fun `an unavailable store is a storage failure`() {
        fixtures.failingStore = true

        listDone.execute(DoneTaskQuery()) shouldBe TaskResult.StorageFailure("listDone")
        fixtures.entries.shouldBeEmpty()
    }
}
