// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.NOW
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskField
import io.github.scriptibus.jofi.tasks.domain.TaskGroup
import io.github.scriptibus.jofi.tasks.domain.TaskGroupsPage
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.github.scriptibus.jofi.tasks.domain.TaskViolation
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
    private val listOpen = ListTaskGroupsUseCase(fixtures.repository, CLOCK, fixtures.redaction)
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
        size: Int = PageRequest.DEFAULT_SIZE,
    ): Paged<Task> = listDone.execute(PageInput(page, size)).shouldBeInstanceOf<TaskResult.Success<Paged<Task>>>().value

    private fun openTasks(): List<Task> =
        listOpen
            .execute(ZoneOffset.UTC, PageInput(), NotesAudience.USER)
            .shouldBeInstanceOf<TaskResult.Success<TaskGroupsPage>>()
            .value.groups
            .flatMap { group -> group.tasks.map { fixtures.tasks.getValue(it.id) } }

    @Test
    fun `lists the done tasks newest completion first, one bounded page at a time`() {
        val first = doneAt(NOW.plusSeconds(10))
        val second = doneAt(NOW.plusSeconds(20))
        val third = doneAt(NOW.plusSeconds(30))

        page().items shouldBe listOf(third, second, first)
        page().info shouldBe PageInfo(0, PageRequest.DEFAULT_SIZE, 3, false)
        page(0, 2) shouldBe Paged(listOf(third, second), PageInfo(0, 2, 3, true))
        page(1, 2) shouldBe Paged(listOf(first), PageInfo(1, 2, 3, false))
        page(2, 2) shouldBe Paged(emptyList(), PageInfo(2, 2, 3, false))
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

        page().items.shouldBeEmpty()
        page().info.total shouldBe 0
        fixtures.tasks.values.count { it.state == TaskState.DONE } shouldBe 0
    }

    @Test
    fun `a task completed by the AI is found under done and reopened, each recorded with its actor`() {
        val task = fixtures.task()
        complete.execute(task.id, 0, Actor.Ai)
        openTasks().shouldBeEmpty()

        val found = page().items.single()
        found.id shouldBe task.id
        found.completedAt shouldBe NOW
        reopen.execute(found.id, found.version, Actor.User).shouldBeInstanceOf<TaskResult.Success<Task>>()

        page().items.shouldBeEmpty()
        page().info.total shouldBe 0
        openTasks().map { it.id } shouldBe listOf(task.id)
        fixtures.tasks.getValue(task.id).state shouldBe TaskState.OPEN
        fixtures.entries.map { it.change.description to it.actor } shouldBe
            listOf("Completed task" to Actor.Ai, "Reopened task" to Actor.User)
    }

    @Test
    fun `an unavailable store is a storage failure`() {
        fixtures.failingStore = true

        listDone.execute(PageInput()) shouldBe TaskResult.StorageFailure("listDone")
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a page or size out of range is invalid and names what is wrong, reading nothing`() {
        fixtures.failingStore = true

        listDone.execute(PageInput(-1, 51)) shouldBe
            TaskResult.Invalid(
                listOf(
                    TaskViolation(TaskField.PAGE, TaskProblem.OUT_OF_RANGE),
                    TaskViolation(TaskField.SIZE, TaskProblem.OUT_OF_RANGE),
                ),
            )
    }
}
