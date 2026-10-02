// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.application.RedactForAiUseCase
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.tasks.application.ListTaskGroupsUseCase
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskGroupKind
import io.github.scriptibus.jofi.tasks.domain.TaskGroupsPage
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskSummary
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The grouped task list over `TaskRepository` on a real PostgreSQL migrated from zero: stored exact times (as
 * `timestamptz`) and bucket dates come back grouped on the viewer's calendar, around midnight, a week's Monday, a
 * month's first day and a clock change, with done tasks and suggestions left out, in one query however many tasks.
 */
class TaskGroupsOnPostgresTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: TaskRepository
    private var queries = 0
    private var created = CREATED

    @BeforeEach
    fun migrateFromZero() {
        val migrated = PostgresTestDatabase.migratedFromZero()
        val counter =
            object : ExecuteListener {
                override fun executeStart(ctx: ExecuteContext) {
                    queries++
                }
            }
        dsl = migrated.configuration().derive(DefaultExecuteListenerProvider(counter)).dsl()
        repository = TaskRepository(dsl)
    }

    @Test
    fun `an exact time at 23 30 in Berlin is today there and in UTC, one at 00 30 only in UTC`() {
        val late = add(exact("2026-09-30T21:30:00Z"))
        val afterMidnight = add(exact("2026-09-30T22:30:00Z"))
        val overdue = add(exact("2026-09-30T09:00:00Z"))

        groups(WEDNESDAY_NOON, BERLIN) shouldBe
            expected(
                TaskGroupKind.OVERDUE to listOf(overdue),
                TaskGroupKind.TODAY to listOf(late),
                TaskGroupKind.THIS_WEEK to listOf(afterMidnight),
            )
        groups(WEDNESDAY_NOON, ZoneOffset.UTC) shouldBe
            expected(TaskGroupKind.OVERDUE to listOf(overdue), TaskGroupKind.TODAY to listOf(late, afterMidnight))
    }

    @Test
    fun `a week is overdue from its next Monday and a month from its next first day, on the viewer's calendar`() {
        val week = add(bucket(BucketSpan.WEEK, "2026-09-28"))
        val nextWeek = add(bucket(BucketSpan.WEEK, "2026-10-05"))
        val october = add(bucket(BucketSpan.MONTH, "2026-10-01"))
        val november = add(bucket(BucketSpan.MONTH, "2026-11-01"))
        val someday = add(TaskTiming.Bucket.SOMEDAY)
        // Sunday 4 October 23:30 in UTC is Monday 00:30 in Berlin.
        val sundayNight = Instant.parse("2026-10-04T23:30:00Z")

        groups(sundayNight, ZoneOffset.UTC) shouldBe
            expected(
                TaskGroupKind.THIS_WEEK to listOf(week),
                TaskGroupKind.NEXT_WEEK to listOf(nextWeek),
                TaskGroupKind.THIS_MONTH to listOf(october),
                TaskGroupKind.LATER to listOf(november),
                TaskGroupKind.SOMEDAY to listOf(someday),
            )
        groups(sundayNight, BERLIN) shouldBe
            expected(
                TaskGroupKind.OVERDUE to listOf(week),
                TaskGroupKind.THIS_WEEK to listOf(nextWeek),
                TaskGroupKind.THIS_MONTH to listOf(october),
                TaskGroupKind.LATER to listOf(november),
                TaskGroupKind.SOMEDAY to listOf(someday),
            )
        // Saturday 31 October 23:30 in UTC is Sunday 1 November 00:30 in Berlin.
        val lastNight = Instant.parse("2026-10-31T23:30:00Z")
        groups(lastNight, BERLIN)[TaskGroupKind.OVERDUE] shouldBe
            listOf(week, nextWeek, october).map { TaskSummary.of(it, it.details.notes) }
        groups(lastNight, BERLIN)[TaskGroupKind.THIS_MONTH] shouldBe
            listOf(november).map { TaskSummary.of(it, it.details.notes) }
    }

    @Test
    fun `the day of an exact time follows the clock change`() {
        // Clocks go back on Sunday 25 October: 22:30Z is Sunday 23:30, 23:30Z is Monday 00:30.
        val sunday = add(exact("2026-10-25T22:30:00Z"))
        val monday = add(exact("2026-10-25T23:30:00Z"))

        groups(Instant.parse("2026-10-19T10:00:00Z"), BERLIN) shouldBe
            expected(TaskGroupKind.THIS_WEEK to listOf(sunday), TaskGroupKind.NEXT_WEEK to listOf(monday))
        groups(Instant.parse("2026-10-25T22:00:00Z"), BERLIN) shouldBe
            expected(TaskGroupKind.TODAY to listOf(sunday), TaskGroupKind.NEXT_WEEK to listOf(monday))
    }

    @Test
    fun `only open tasks are listed, all in one query`() {
        val open = List(20) { add(bucket(BucketSpan.DAY, "2026-09-30")) }
        val done = Task.create(newId(), details(TaskTiming.Bucket.SOMEDAY), TaskOrigin.Manual, CREATED)
        repository.add(done)
        repository.update(done.moved(TaskTransition.COMPLETE))
        val suggestion = TaskOrigin.Suggested("follow-up", "application:${UUID.randomUUID()}")
        repository.add(Task.suggest(newId(), details(TaskTiming.Bucket.SOMEDAY), suggestion, CREATED))
        queries = 0

        groups(WEDNESDAY_NOON, BERLIN) shouldBe expected(TaskGroupKind.TODAY to open)
        queries shouldBe 1
    }

    private fun groups(
        now: Instant,
        zone: ZoneId,
    ): Map<TaskGroupKind, List<TaskSummary>> {
        val result =
            ListTaskGroupsUseCase(repository, Clock.fixed(now, ZoneOffset.UTC), UNFLAGGED)
                .execute(zone, PageInput(0, PageRequest.MAX_SIZE), NotesAudience.USER)
        return result
            .shouldBeInstanceOf<TaskResult.Success<TaskGroupsPage>>()
            .value
            .groups
            .associate { it.kind to it.tasks }
    }

    /** Every group, empty but for [filled]. */
    private fun expected(vararg filled: Pair<TaskGroupKind, List<Task>>): Map<TaskGroupKind, List<TaskSummary>> =
        TaskGroupKind.entries.associateWith { emptyList<TaskSummary>() } +
            filled.map { (kind, tasks) -> kind to tasks.map { TaskSummary.of(it, it.details.notes) } }

    private fun add(timing: TaskTiming): Task =
        Task.create(newId(), details(timing), TaskOrigin.Manual, nextCreated()).also {
            repository.add(it) shouldBe TaskStoreResult.Success(Unit)
        }

    /** Each task a second younger than the one before, so the order on equal deadlines is known. */
    private fun nextCreated(): Instant = created.also { created = created.plusSeconds(1) }

    private fun Task.moved(transition: TaskTransition): Task =
        (apply(transition, CREATED) as TaskStateChange.Changed).task

    private fun details(timing: TaskTiming) = TaskDetails("Follow up", timing)

    private fun exact(dueAt: String) = TaskTiming.Exact(Instant.parse(dueAt), BERLIN)

    private fun bucket(
        span: BucketSpan,
        startsOn: String,
    ) = TaskTiming.Bucket(span, LocalDate.parse(startsOn))

    private fun newId() = TaskId(UUID.randomUUID())

    private companion object {
        val UNFLAGGED =
            RedactForAiUseCase(
                object : AiVisibilityPort {
                    override fun rulesFor(sources: Set<ContentSource>) = AiVisibilityResult.Known(NeverSendRules.NONE)
                },
            )

        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        val CREATED: Instant = Instant.parse("2026-09-01T08:00:00Z")

        /** Wednesday 30 September 2026, noon in Berlin. */
        val WEDNESDAY_NOON: Instant = Instant.parse("2026-09-30T10:00:00Z")
    }
}
