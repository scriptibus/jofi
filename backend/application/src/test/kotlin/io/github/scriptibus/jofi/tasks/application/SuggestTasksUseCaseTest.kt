// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.applications.application.port.api.DescribeApplicationEventPort
import io.github.scriptibus.jofi.applications.application.port.api.FindSuggestionFactsPort
import io.github.scriptibus.jofi.applications.application.port.api.FindSuggestionFactsPort.FollowUpDue
import io.github.scriptibus.jofi.applications.application.port.api.FindSuggestionFactsPort.InterviewAhead
import io.github.scriptibus.jofi.applications.application.port.api.FindSuggestionFactsPort.OfferOpen
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.DomainEvent
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.NOW
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.SuggestionRun
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskSuggestionRules
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The suggestion rules of #95 on a fixed clock, accepting a suggestion, and queuing a run after an event. */
class SuggestTasksUseCaseTest {
    private val fixtures = TaskFixtures()
    private val application = TaskFixtures.APPLICATION.value
    private val interview = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val silentSince = Instant.parse("2026-09-10T08:00:00Z")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private val followUps = mutableListOf<FollowUpDue>()
    private val interviews = mutableListOf<InterviewAhead>()
    private val offers = mutableListOf<OfferOpen>()
    private var available = true
    private val facts =
        object : FindSuggestionFactsPort {
            override fun execute(at: Instant): FindSuggestionFactsPort.Facts {
                check(at == NOW) { "Asked at $at" }
                return if (available) {
                    FindSuggestionFactsPort.Facts.Found(followUps.toList(), interviews.toList(), offers.toList())
                } else {
                    FindSuggestionFactsPort.Facts.Unavailable
                }
            }
        }
    private val suggest =
        SuggestTasksUseCase(facts, fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val accept =
        AcceptTaskSuggestionUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)

    private fun run(): SuggestionRun = suggest.execute().shouldBeInstanceOf<TaskResult.Success<SuggestionRun>>().value

    private fun waiting(rule: String): List<Task> =
        fixtures.tasks.values.filter {
            it.state == TaskState.SUGGESTED && (it.origin as TaskOrigin.Suggested).rule == rule
        }

    private fun interviewOn(startsAt: String) =
        InterviewAhead(interview, application, "Backend Engineer", Instant.parse(startsAt), tokyo)

    @Test
    fun `each rule suggests once for its fact, recorded as the rule, and a second run adds nothing`() {
        followUps += FollowUpDue(application, "Backend Engineer", silentSince, Instant.parse("2026-09-24T08:00:00Z"))
        interviews += interviewOn("2026-10-19T23:00:00Z")
        offers += OfferOpen(application, "Backend Engineer", LocalDate.of(2026, 10, 9))

        run() shouldBe SuggestionRun(suggested = 3, dismissed = 0)
        run() shouldBe SuggestionRun(suggested = 0, dismissed = 0)

        val byRule = fixtures.tasks.values.associateBy { (it.origin as TaskOrigin.Suggested).rule }
        byRule.keys shouldBe TaskSuggestionRules.RULES
        byRule.getValue("follow-up").details.timing shouldBe day(2026, 9, 24)
        byRule.getValue("interview-preparation").details.timing shouldBe day(2026, 10, 19)
        byRule.getValue("offer-answer").details.timing shouldBe day(2026, 10, 8)
        byRule.values.forEach {
            it.state shouldBe TaskState.SUGGESTED
            it.details.link shouldBe ApplicationRef(application)
            it.createdAt shouldBe NOW
        }
        fixtures.entries.map { it.actor } shouldContainExactlyInAnyOrder
            listOf(Actor.System("follow-up"), Actor.System("interview-preparation"), Actor.System("offer-answer"))
        fixtures.entries.map { it.change.description }.distinct() shouldBe listOf("Suggested task")
    }

    @Test
    fun `a rescheduled interview dismisses the old preparation as the rule and suggests the new day`() {
        interviews += interviewOn("2026-10-19T23:00:00Z")
        run()
        val old = waiting("interview-preparation").single()

        interviews[0] = interviewOn("2026-10-21T23:00:00Z")
        run() shouldBe SuggestionRun(suggested = 1, dismissed = 1)

        fixtures.tasks.getValue(old.id).state shouldBe TaskState.DISMISSED
        waiting("interview-preparation").single().details.timing shouldBe day(2026, 10, 21)
        val dismissal = fixtures.entries.first { it.change.description == "Dismissed obsolete suggestion" }
        dismissal.actor shouldBe Actor.System("interview-preparation")
        dismissal.change.fieldChanges shouldContainExactly listOf(FieldChange("state", "SUGGESTED", "DISMISSED"))
    }

    @Test
    fun `facts that are gone dismiss only waiting suggestions of these rules, never accepted or other rules' ones`() {
        followUps += FollowUpDue(application, "Backend Engineer", silentSince, Instant.parse("2026-09-24T08:00:00Z"))
        offers += OfferOpen(application, "Backend Engineer", LocalDate.of(2026, 10, 9))
        run()
        val followUp = waiting("follow-up").single()
        accept.execute(followUp.id, 0, Actor.User)
        val ghosted =
            Task.suggest(
                TaskId(UUID.randomUUID()),
                followUp.details,
                TaskOrigin.Suggested("ghosted-suggestion", "application:x"),
                NOW,
            )
        fixtures.tasks[ghosted.id] = ghosted
        followUps.clear()
        offers.clear()

        run() shouldBe SuggestionRun(suggested = 0, dismissed = 1)

        fixtures.tasks.getValue(followUp.id).state shouldBe TaskState.OPEN
        fixtures.tasks.getValue(ghosted.id).state shouldBe TaskState.SUGGESTED
        fixtures.tasks.values
            .single { (it.origin as TaskOrigin.Suggested).rule == "offer-answer" }
            .state shouldBe
            TaskState.DISMISSED
    }

    @Test
    fun `a dismissed suggestion is not made again, and unavailable facts fail the run`() {
        offers += OfferOpen(application, "Backend Engineer", LocalDate.of(2026, 10, 9))
        run()
        val offer = waiting("offer-answer").single()
        DismissTaskSuggestionUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
            .execute(offer.id, 0, Actor.User)

        run() shouldBe SuggestionRun(suggested = 0, dismissed = 0)
        fixtures.tasks.values shouldHaveSize 1

        available = false
        suggest.execute() shouldBe TaskResult.StorageFailure("find suggestion facts")
    }

    @Test
    fun `accepting opens a suggestion as the actor with its state change, once`() {
        offers += OfferOpen(application, "Backend Engineer", LocalDate.of(2026, 10, 9))
        run()
        val offer = waiting("offer-answer").single()

        val opened = accept.execute(offer.id, 0, Actor.User).shouldBeInstanceOf<TaskResult.Success<Task>>().value

        opened.state shouldBe TaskState.OPEN
        opened.version shouldBe 1
        opened.origin shouldBe offer.origin
        fixtures.entries.last().let {
            it.actor shouldBe Actor.User
            it.change.description shouldBe "Accepted suggestion"
            it.change.fieldChanges shouldContainExactly listOf(FieldChange("state", "SUGGESTED", "OPEN"))
        }
        accept.execute(offer.id, 1, Actor.User) shouldBe TaskResult.Success(opened)
        accept.execute(offer.id, 0, Actor.User) shouldBe TaskResult.VersionConflict
        fixtures.entries shouldHaveSize 2
        val manual = fixtures.task()
        accept.execute(manual.id, 0, Actor.User) shouldBe TaskResult.Success(manual)
        val dismissed =
            (offer.copy(id = TaskId(UUID.randomUUID())).apply(TaskTransition.DISMISS, NOW) as TaskStateChange.Changed)
                .task
        fixtures.tasks[dismissed.id] = dismissed
        accept.execute(dismissed.id, 1, Actor.User) shouldBe
            TaskResult.InvalidTransition(TaskState.DISMISSED, TaskState.OPEN)
    }

    @Test
    fun `an applications event queues a run, any other event nothing, and the schedule is daily`() {
        val jobs = mockk<JobSchedulerPort>()
        val events = mockk<DescribeApplicationEventPort>()
        val applied = object : DomainEvent {}
        val other = object : DomainEvent {}
        every { events.execute(applied) } returns
            DescribeApplicationEventPort.ApplicationEvent(DescribeApplicationEventPort.Kind.STATUS_CHANGED, application)
        every { events.execute(other) } returns null
        every { jobs.enqueue(TaskSuggestionRules.REQUEST) } returns JobResult.Success(JobId(UUID.randomUUID()))
        every {
            jobs.scheduleRecurring(
                TaskSuggestionRules.RECURRING_ID,
                TaskSuggestionRules.SCHEDULE,
                TaskSuggestionRules.REQUEST,
            )
        } returns JobResult.Success(Unit)
        val request = RequestTaskSuggestionsUseCase(events, jobs)

        request.execute(applied).shouldBeInstanceOf<JobResult.Success<JobId>>()
        request.execute(other) shouldBe null
        ScheduleTaskSuggestionsUseCase(jobs).execute() shouldBe JobResult.Success(Unit)

        verify(exactly = 1) { jobs.enqueue(TaskSuggestionRules.REQUEST) }
    }

    private fun day(
        year: Int,
        month: Int,
        day: Int,
    ) = TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(year, month, day))
}
