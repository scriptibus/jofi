// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.APPLICATION
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.NOW
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskField
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskInput
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTimingInput
import io.github.scriptibus.jofi.tasks.domain.TaskViolation
import io.github.scriptibus.jofi.tasks.domain.TimeBucket
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class TaskUseCasesTest {
    private val fixtures = TaskFixtures()
    private val create = CreateTaskUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val update = UpdateTaskUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val get = GetTaskUseCase(fixtures.repository)
    private val complete = CompleteTaskUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val reopen = ReopenTaskUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)

    private fun bucket(
        bucket: TimeBucket,
        zone: String = "Europe/Berlin",
    ) = TaskTimingInput(zone, bucket = bucket)

    private fun created(result: TaskResult<Task>): Task = result.shouldBeInstanceOf<TaskResult.Success<Task>>().value

    @Test
    fun `creating stores an open task with the bucket of today in the user's zone, recorded without its text`() {
        val input = TaskInput("  Café anrufen ", bucket(TimeBucket.THIS_MONTH), APPLICATION, "Ask about *salary*")

        val task = created(create.execute(input, TaskOrigin.Manual, Actor.User))

        task.details.title shouldBe "Café anrufen"
        task.details.timing shouldBe TaskTiming.Bucket(BucketSpan.MONTH, LocalDate.of(2026, 9, 1))
        task.state shouldBe TaskState.OPEN
        task.origin shouldBe TaskOrigin.Manual
        task.createdAt shouldBe NOW
        fixtures.tasks[task.id] shouldBe task
        val entry = fixtures.entries.single()
        entry.entity shouldBe task.id.toEntityRef()
        entry.actor shouldBe Actor.User
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Created task"
        entry.change.fieldChanges shouldContainExactly
            listOf(
                FieldChange("timing", null, "MONTH 2026-09-01"),
                FieldChange("link", null, "application:${APPLICATION.value}"),
            )
        entry.toString() shouldNotContain "anrufen"
        entry.toString() shouldNotContain "salary"
    }

    @Test
    fun `today is the day in the zone the client sends, not the server's`() {
        val input = TaskInput("Call", bucket(TimeBucket.TODAY, "Asia/Tokyo"))

        val tokyo = created(create.execute(input, TaskOrigin.Chat, Actor.Ai))

        tokyo.details.timing shouldBe TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2026, 10, 1))
        tokyo.origin shouldBe TaskOrigin.Chat
        fixtures.entries.single().actor shouldBe Actor.Ai
    }

    @Test
    fun `an exact due time is the instant of the wall-clock time in its zone`() {
        val timing = TaskTimingInput("Europe/Berlin", localDue = LocalDateTime.of(2026, 10, 5, 10, 0))

        val task = created(create.execute(TaskInput("Call", timing), TaskOrigin.Manual, Actor.User))

        task.details.timing shouldBe TaskTiming.Exact(Instant.parse("2026-10-05T08:00:00Z"), ZoneId.of("Europe/Berlin"))
        fixtures.entries
            .single()
            .change.fieldChanges shouldContainExactly
            listOf(FieldChange("timing", null, "2026-10-05T08:00:00Z Europe/Berlin"))
    }

    @Test
    fun `invalid input and a link to nothing store nothing`() {
        val invalid = TaskInput(" ", TaskTimingInput("Mars/Olympus"))
        create.execute(invalid, TaskOrigin.Manual, Actor.User).shouldBeInstanceOf<TaskResult.Invalid>()

        val missing = TaskInput("Call", bucket(TimeBucket.SOMEDAY), CompanyRef(UUID.randomUUID()))
        create.execute(missing, TaskOrigin.Manual, Actor.User) shouldBe
            TaskResult.Invalid(listOf(TaskViolation(TaskField.LINK, TaskProblem.NOT_FOUND)))

        fixtures.tasks.size shouldBe 0
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a failing changelog or store rolls the create back`() {
        fixtures.failingChangelog = true
        create.execute(TaskInput("Call", bucket(TimeBucket.TODAY)), TaskOrigin.Manual, Actor.User) shouldBe
            TaskResult.StorageFailure("changelog")
        fixtures.tasks.size shouldBe 0

        fixtures.failingChangelog = false
        fixtures.failingStore = true
        create.execute(TaskInput("Call", bucket(TimeBucket.TODAY)), TaskOrigin.Manual, Actor.User) shouldBe
            TaskResult.StorageFailure("add")
    }

    @Test
    fun `reading answers the task or not found`() {
        val task = fixtures.task()

        get.execute(task.id) shouldBe TaskResult.Success(task)
        get.execute(TaskId(UUID.randomUUID())) shouldBe TaskResult.NotFound
    }

    @Test
    fun `updating replaces all details and names the title and notes without their text`() {
        val task = fixtures.task(link = APPLICATION)
        val timing = TaskTimingInput("UTC", localDue = LocalDateTime.of(2026, 10, 2, 9, 0))
        val input = TaskInput("Send portfolio", timing, notes = "PDF")

        val edited = created(update.execute(task.id, input, task.version, Actor.ExternalClient("claude-desktop")))

        edited.version shouldBe 1
        edited.updatedAt shouldBe NOW
        edited.details.link shouldBe null
        fixtures.tasks[task.id] shouldBe edited
        val entry = fixtures.entries.single()
        entry.actor shouldBe Actor.ExternalClient("claude-desktop")
        entry.change.description shouldBe "Edited task; also changed: title, notes"
        entry.change.fieldChanges shouldContainExactly
            listOf(
                FieldChange("timing", "SOMEDAY", "2026-10-02T09:00:00Z UTC"),
                FieldChange("link", "application:${APPLICATION.value}", null),
            )
    }

    @Test
    fun `an unchanged edit stores nothing, but the version is checked first`() {
        val task = fixtures.task()
        val same = TaskInput("Call back", bucket(TimeBucket.SOMEDAY))

        update.execute(task.id, same, task.version, Actor.User) shouldBe TaskResult.Success(task)
        fixtures.entries.shouldBeEmpty()

        update.execute(task.id, same, task.version + 1, Actor.User) shouldBe TaskResult.VersionConflict
        update.execute(task.id, TaskInput(" ", bucket(TimeBucket.SOMEDAY)), 7, Actor.User) shouldBe
            TaskResult.VersionConflict
        update.execute(TaskId(UUID.randomUUID()), same, 0, Actor.User) shouldBe TaskResult.NotFound
    }

    @Test
    fun `a change that slipped in between read and write is a version conflict`() {
        val task = fixtures.task()
        fixtures.concurrentVersion = 1

        update.execute(task.id, TaskInput("Other", bucket(TimeBucket.TODAY)), 0, Actor.User) shouldBe
            TaskResult.VersionConflict
        fixtures.tasks[task.id] shouldBe task
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `completing and reopening move the task and record the state`() {
        val task = fixtures.task()

        val done = created(complete.execute(task.id, 0, Actor.User))
        done.state shouldBe TaskState.DONE
        done.completedAt shouldBe NOW
        done.version shouldBe 1

        val open = created(reopen.execute(task.id, 1, Actor.Ai))
        open.state shouldBe TaskState.OPEN
        open.completedAt shouldBe null
        open.version shouldBe 2
        fixtures.tasks[task.id] shouldBe open

        fixtures.entries.map { it.change.description to it.actor } shouldContainExactly
            listOf("Completed task" to Actor.User, "Reopened task" to Actor.Ai)
        fixtures.entries.map { it.change.fieldChanges.single() } shouldContainExactly
            listOf(FieldChange("state", "OPEN", "DONE"), FieldChange("state", "DONE", "OPEN"))
    }

    @Test
    fun `a task in the target state already is unchanged, after the version check`() {
        val task = fixtures.task()

        reopen.execute(task.id, 0, Actor.User) shouldBe TaskResult.Success(task)
        reopen.execute(task.id, 3, Actor.User) shouldBe TaskResult.VersionConflict
        complete.execute(TaskId(UUID.randomUUID()), 0, Actor.User) shouldBe TaskResult.NotFound
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `completing and reopening never do another transition's job`() {
        val suggestion = TaskOrigin.Suggested("follow-up", "application:${APPLICATION.value}")
        val suggested = Task.suggest(TaskId(UUID.randomUUID()), fixtures.task().details, suggestion, NOW)
        fixtures.tasks[suggested.id] = suggested

        complete.execute(suggested.id, 0, Actor.User) shouldBe
            TaskResult.InvalidTransition(TaskState.SUGGESTED, TaskState.DONE)
        reopen.execute(suggested.id, 0, Actor.User) shouldBe
            TaskResult.InvalidTransition(TaskState.SUGGESTED, TaskState.OPEN)
        fixtures.tasks[suggested.id] shouldBe suggested
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a failing changelog rolls the move back`() {
        val task = fixtures.task()
        fixtures.failingChangelog = true

        complete.execute(task.id, 0, Actor.User) shouldBe TaskResult.StorageFailure("changelog")
        fixtures.tasks[task.id] shouldBe task
    }
}
