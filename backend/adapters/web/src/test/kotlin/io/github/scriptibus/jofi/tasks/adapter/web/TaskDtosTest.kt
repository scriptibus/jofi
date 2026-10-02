// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.PageResponse
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownDetails
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.CountdownInput
import io.github.scriptibus.jofi.tasks.domain.CountdownKind
import io.github.scriptibus.jofi.tasks.domain.CountdownTarget
import io.github.scriptibus.jofi.tasks.domain.DashboardCountdown
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskGroupKind
import io.github.scriptibus.jofi.tasks.domain.TaskGroupsPage
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskInput
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskSummary
import io.github.scriptibus.jofi.tasks.domain.TaskSummaryGroup
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTimingInput
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.github.scriptibus.jofi.tasks.domain.TimeBucket
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.reflect.KClass

class TaskDtosTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000011")
    private val contact = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val exact = TaskTiming.Exact(Instant.parse("2026-10-05T08:00:00Z"), berlin)
    private val task =
        Task.create(
            TaskId(uuid),
            TaskDetails("Secret title", exact, ContactRef(contact), "Secret notes"),
            TaskOrigin.Chat,
            at,
        )

    @ParameterizedTest
    @MethodSource("enumPairs")
    fun `every API enum has exactly the constants of its domain enum`(
        api: KClass<out Enum<*>>,
        domain: KClass<out Enum<*>>,
    ) {
        api.java.enumConstants.map { it.name } shouldBe domain.java.enumConstants.map { it.name }
    }

    @Test
    fun `a request becomes domain input unchanged, validation is the domain's job`() {
        val local = LocalDateTime.parse("2026-10-05T10:00")
        val request =
            TaskRequest(
                " Call ",
                TaskTimingRequest(" UTC ", TaskBucket.NEXT_WEEK, local),
                TaskLinkDto(TaskLinkType.APPLICATION, uuid),
                " ",
            )

        request.toInput() shouldBe
            TaskInput(" Call ", TaskTimingInput(" UTC ", TimeBucket.NEXT_WEEK, local), ApplicationRef(uuid), " ")
        TaskLinkDto(TaskLinkType.COMPANY, uuid).toLink() shouldBe CompanyRef(uuid)
        TaskLinkDto(TaskLinkType.CONTACT, uuid).toLink() shouldBe ContactRef(uuid)
        listOf(ApplicationRef(uuid), CompanyRef(uuid), ContactRef(uuid)).forEach {
            TaskLinkDto.from(it).toLink() shouldBe it
        }
    }

    @Test
    fun `a task becomes a response with its timing, link and origin`() {
        TaskResponse.from(task) shouldBe
            TaskResponse(
                uuid,
                "Secret title",
                TaskTimingResponse(exact.dueAt, LocalDateTime.parse("2026-10-05T10:00"), "Europe/Berlin"),
                TaskLinkDto(TaskLinkType.CONTACT, contact),
                "Secret notes",
                TaskOriginKind.CHAT,
                null,
                TaskStatus.OPEN,
                null,
                0,
                at,
                at,
            )
        TaskResponse.from(task.copy(origin = TaskOrigin.Manual)).origin shouldBe TaskOriginKind.MANUAL
    }

    @Test
    fun `a suggestion shows its rule, a done task when it was done`() {
        val suggested =
            Task.suggest(
                TaskId(uuid),
                task.details,
                TaskOrigin.Suggested("follow-up", "application:a1"),
                at,
            )
        val done = (task.apply(TaskTransition.COMPLETE, at) as TaskStateChange.Changed).task

        TaskResponse.from(suggested).origin shouldBe TaskOriginKind.SUGGESTED
        TaskResponse.from(suggested).suggestionRule shouldBe "follow-up"
        TaskResponse.from(suggested).status shouldBe TaskStatus.SUGGESTED
        TaskResponse.from(done).completedAt shouldBe at
    }

    @Test
    fun `a bucket shows its span and days`() {
        TaskTimingResponse.from(TaskTiming.Bucket(BucketSpan.WEEK, LocalDate.parse("2026-09-28"))) shouldBe
            TaskTimingResponse(
                span = TaskSpan.WEEK,
                startsOn = LocalDate.parse("2026-09-28"),
                endsBefore = LocalDate.parse("2026-10-05"),
            )
        TaskTimingResponse.from(TaskTiming.Bucket.SOMEDAY) shouldBe TaskTimingResponse(span = TaskSpan.SOMEDAY)
    }

    @Test
    fun `lists and groups keep their order and carry where the page sits`() {
        val summary = TaskSummary.of(task, task.details.notes)
        val info = PageInfo(1, 2, 5, true)

        TaskListResponse
            .from(Paged(listOf(summary), info))
            .tasks
            .single()
            .id shouldBe uuid
        TaskGroupListResponse.from(
            TaskGroupsPage(
                listOf(
                    TaskSummaryGroup(TaskGroupKind.OVERDUE, listOf(summary)),
                    TaskSummaryGroup(TaskGroupKind.SOMEDAY, emptyList()),
                ),
                info,
            ),
        ) shouldBe
            TaskGroupListResponse(
                listOf(
                    TaskGroupResponse(TaskDueGroup.OVERDUE, listOf(TaskSummaryResponse.from(summary))),
                    TaskGroupResponse(TaskDueGroup.SOMEDAY, emptyList()),
                ),
                PageResponse(1, 2, 5, true),
            )
    }

    @Test
    fun `a summary shows the excerpt and whether notes were cut, never the notes`() {
        val long = "n".repeat(TextExcerpt.MAX_LENGTH + 1)
        val cut = TaskSummaryResponse.from(TaskSummary.of(task.copy(details = task.details.copy(notes = long)), long))
        val short = TaskSummaryResponse.from(TaskSummary.of(task.copy(details = task.details.copy(notes = "hi")), "hi"))
        val none = TaskSummaryResponse.from(TaskSummary.of(task.copy(details = task.details.copy(notes = null)), null))

        cut.notesExcerpt shouldBe "n".repeat(TextExcerpt.MAX_LENGTH)
        cut.notesTruncated shouldBe true
        short.notesExcerpt shouldBe "hi"
        short.notesTruncated shouldBe false
        none.notesExcerpt shouldBe null
        none.notesTruncated shouldBe false
        cut.toString() shouldNotContain "nnnn"
    }

    @Test
    fun `countdowns become requests and responses`() {
        val date = LocalDate.parse("2026-12-31")
        val countdown = Countdown.create(CountdownId(uuid), CountdownDetails("Secret notice", date), at)

        CountdownRequest(" Notice ", date).toInput() shouldBe CountdownInput(" Notice ", date)
        CountdownListResponse.from(listOf(countdown)).countdowns shouldBe
            listOf(CountdownResponse(uuid, "Secret notice", date, 0, at, at))
    }

    @Test
    fun `a dashboard countdown at an instant shows it in its zone`() {
        val interview =
            DashboardCountdown(
                CountdownKind.NEXT_INTERVIEW,
                "Backend",
                CountdownTarget.At(exact.dueAt, berlin),
                EntityRef("interview", "f1"),
                uuid,
            )

        DashboardCountdownResponse.from(interview) shouldBe
            DashboardCountdownResponse(
                CountdownSource.NEXT_INTERVIEW,
                "Backend",
                null,
                exact.dueAt,
                LocalDateTime.parse("2026-10-05T10:00"),
                "Europe/Berlin",
                "interview",
                "f1",
                uuid,
            )
    }

    @Test
    fun `a dashboard countdown on a day has only the date`() {
        val day = LocalDate.parse("2026-10-31")
        val deadline =
            DashboardCountdown(
                CountdownKind.APPLICATION_DEADLINE,
                "Backend",
                CountdownTarget.OnDay(day),
                EntityRef("application", "b1"),
            )

        DashboardCountdownListResponse.from(listOf(deadline)).countdowns shouldBe
            listOf(
                DashboardCountdownResponse(
                    CountdownSource.APPLICATION_DEADLINE,
                    "Backend",
                    day,
                    null,
                    null,
                    null,
                    "application",
                    "b1",
                    null,
                ),
            )
    }

    @Test
    fun `nothing personal is printed`() {
        val request = TaskRequest("Secret", TaskTimingRequest("UTC", TaskBucket.TODAY), notes = "Secret")
        val countdown = CountdownResponse(uuid, "Secret", LocalDate.parse("2026-12-31"), 0, at, at)
        val dashboard =
            DashboardCountdownResponse(CountdownSource.CUSTOM, "Secret", null, null, null, null, "countdown", "x", null)

        listOf(
            request,
            TaskResponse.from(task),
            CountdownRequest("Secret", LocalDate.parse("2026-12-31")),
            countdown,
            dashboard,
        ).forEach {
            it.toString() shouldNotContain "Secret"
            it.toString() shouldNotContain contact.toString()
        }
    }

    companion object {
        @JvmStatic
        fun enumPairs(): List<Arguments> =
            listOf(
                Arguments.of(TaskBucket::class, TimeBucket::class),
                Arguments.of(TaskSpan::class, BucketSpan::class),
                Arguments.of(TaskStatus::class, TaskState::class),
                Arguments.of(TaskDueGroup::class, TaskGroupKind::class),
                Arguments.of(CountdownSource::class, CountdownKind::class),
            )
    }
}
