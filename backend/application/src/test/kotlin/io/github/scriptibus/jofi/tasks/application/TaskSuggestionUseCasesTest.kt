// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.job.CronSchedule
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.RecurringJobId
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.CREATED
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.NOW
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.GhostedSuggestion
import io.github.scriptibus.jofi.tasks.domain.GhostedSuggestionRun
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The Ghosted suggestion run (#85) and the suggestion list and dismissal, on a fixed clock. */
class TaskSuggestionUseCasesTest {
    private val fixtures = TaskFixtures()
    private val silent = mutableListOf<FindGhostedCandidatesPort.Candidate>()
    private var candidatesAvailable = true
    private val askedAt = mutableListOf<Instant>()
    private val candidates =
        object : FindGhostedCandidatesPort {
            override fun execute(at: Instant): FindGhostedCandidatesPort.Candidates {
                askedAt += at
                return if (candidatesAvailable) {
                    FindGhostedCandidatesPort.Candidates.Found(silent.toList())
                } else {
                    FindGhostedCandidatesPort.Candidates.Unavailable
                }
            }
        }
    private val suggest =
        SuggestGhostedApplicationsUseCase(
            candidates,
            fixtures.repository,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val dismiss =
        DismissTaskSuggestionUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val list = ListSuggestedTasksUseCase(fixtures.repository)

    private val application = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val silentSince = Instant.parse("2026-06-01T10:00:00.5Z")

    private fun run(): GhostedSuggestionRun =
        suggest.execute().shouldBeInstanceOf<TaskResult.Success<GhostedSuggestionRun>>().value

    private fun suggestions(): List<Task> = fixtures.tasks.values.filter { it.origin is TaskOrigin.Suggested }

    @Test
    fun `a silent application gets one suggestion, recorded as the rule, asked with the clock's time`() {
        silent += FindGhostedCandidatesPort.Candidate(application, "Backend Engineer", silentSince)

        run() shouldBe GhostedSuggestionRun(suggested = 1, dismissed = 0)

        val task = suggestions().single()
        task.state shouldBe TaskState.SUGGESTED
        task.origin shouldBe
            TaskOrigin.Suggested("ghosted-suggestion", "application:$application:2026-06-01T10:00:00.500Z")
        task.details shouldBe
            TaskDetails("Mark as Ghosted: Backend Engineer", TaskTiming.Bucket.SOMEDAY, ApplicationRef(application))
        task.createdAt shouldBe NOW
        askedAt shouldContainExactly listOf(NOW)
        val entry = fixtures.entries.single()
        entry.actor shouldBe Actor.System("ghosted-suggestion")
        entry.entity shouldBe task.id.toEntityRef()
        entry.change.description shouldBe "Suggested task"
        entry.change.fieldChanges shouldContainExactly
            listOf(FieldChange("timing", null, "SOMEDAY"), FieldChange("link", null, "application:$application"))
    }

    @Test
    fun `running again is idempotent, and a dismissed silence is not suggested again`() {
        silent += FindGhostedCandidatesPort.Candidate(application, "Backend Engineer", silentSince)
        run()

        run() shouldBe GhostedSuggestionRun(suggested = 0, dismissed = 0)
        suggestions() shouldHaveSize 1
        fixtures.entries shouldHaveSize 1

        val suggestion = suggestions().single()
        dismiss.execute(suggestion.id, suggestion.version, Actor.User)
        run() shouldBe GhostedSuggestionRun(suggested = 0, dismissed = 0)
        suggestions().single().state shouldBe TaskState.DISMISSED
    }

    @Test
    fun `a new silence after new activity is a new suggestion, and the old one is dismissed as obsolete`() {
        silent += FindGhostedCandidatesPort.Candidate(application, "Backend Engineer", silentSince)
        run()
        val old = suggestions().single()

        silent[0] = silent[0].copy(silentSince = silentSince.plusSeconds(86_400))
        run() shouldBe GhostedSuggestionRun(suggested = 1, dismissed = 1)

        fixtures.tasks.getValue(old.id).state shouldBe TaskState.DISMISSED
        suggestions().count { it.state == TaskState.SUGGESTED } shouldBe 1
        val dismissal = fixtures.entries.last()
        dismissal.actor shouldBe GhostedSuggestion.ACTOR
        dismissal.change.description shouldBe "Dismissed obsolete suggestion"
        dismissal.change.fieldChanges shouldContainExactly listOf(FieldChange("state", "SUGGESTED", "DISMISSED"))
    }

    @Test
    fun `a suggestion whose application answered or moved on is dismissed, other rules' suggestions stay`() {
        silent += FindGhostedCandidatesPort.Candidate(application, "Backend Engineer", silentSince)
        run()
        val other = suggestion("follow-up")
        val ghosted = suggestions().single { it.origin != other.origin }
        silent.clear()

        run() shouldBe GhostedSuggestionRun(suggested = 0, dismissed = 1)

        fixtures.tasks.getValue(other.id).state shouldBe TaskState.SUGGESTED
        fixtures.tasks.getValue(ghosted.id).state shouldBe TaskState.DISMISSED
    }

    @Test
    fun `accepted or done ghosted tasks are never dismissed by the run`() {
        silent += FindGhostedCandidatesPort.Candidate(application, "Backend Engineer", silentSince)
        run()
        val suggested = suggestions().single()
        fixtures.tasks[suggested.id] = suggested.copy(state = TaskState.OPEN, version = 1)
        silent.clear()

        run() shouldBe GhostedSuggestionRun(suggested = 0, dismissed = 0)
        fixtures.tasks.getValue(suggested.id).state shouldBe TaskState.OPEN
    }

    @Test
    fun `unavailable candidates or a failing store fail the run, and a failing changelog stores nothing`() {
        candidatesAvailable = false
        suggest.execute() shouldBe TaskResult.StorageFailure("find ghosted candidates")

        candidatesAvailable = true
        silent += FindGhostedCandidatesPort.Candidate(application, "Backend Engineer", silentSince)
        fixtures.failingChangelog = true
        suggest.execute() shouldBe TaskResult.StorageFailure("changelog")
        suggestions().shouldBeEmpty()

        fixtures.failingChangelog = false
        fixtures.failingStore = true
        suggest.execute().shouldBeInstanceOf<TaskResult.StorageFailure>()
        suggestions().shouldBeEmpty()
    }

    @Test
    fun `the list shows waiting suggestions newest first`() {
        val older = suggestion("follow-up", created = CREATED)
        val newer = suggestion("interview-prep", created = CREATED.plusSeconds(60))
        fixtures.task()

        list.execute() shouldBe TaskResult.Success(listOf(newer, older))
    }

    @Test
    fun `dismissing records the state with the actor, but an open task cannot be dismissed`() {
        val waiting = suggestion("follow-up")

        val dismissed = dismiss.execute(waiting.id, 0, Actor.User).shouldBeInstanceOf<TaskResult.Success<Task>>().value

        dismissed.state shouldBe TaskState.DISMISSED
        dismissed.version shouldBe 1
        fixtures.entries.single().let {
            it.actor shouldBe Actor.User
            it.change.fieldChanges shouldContainExactly listOf(FieldChange("state", "SUGGESTED", "DISMISSED"))
        }
        dismiss.execute(waiting.id, 1, Actor.User) shouldBe TaskResult.Success(dismissed)
        dismiss.execute(waiting.id, 0, Actor.User) shouldBe TaskResult.VersionConflict
        fixtures.entries shouldHaveSize 1
        val open = fixtures.task()
        dismiss.execute(open.id, 0, Actor.User) shouldBe
            TaskResult.InvalidTransition(TaskState.OPEN, TaskState.DISMISSED)
    }

    @Test
    fun `scheduling registers the daily run with its random delay`() {
        val scheduled = mutableListOf<Triple<RecurringJobId, CronSchedule, JobRequest>>()
        val jobs =
            object : JobSchedulerPort {
                override fun enqueue(request: JobRequest): JobResult<JobId> = error("Not used")

                override fun scheduleRecurring(
                    id: RecurringJobId,
                    schedule: CronSchedule,
                    request: JobRequest,
                ): JobResult<Unit> = JobResult.Success(Unit).also { scheduled += Triple(id, schedule, request) }

                override fun cancel(id: JobId): JobResult<Unit> = error("Not used")

                override fun cancelRecurring(id: RecurringJobId): JobResult<Unit> = error("Not used")
            }

        ScheduleGhostedSuggestionUseCase(jobs).execute() shouldBe JobResult.Success(Unit)

        scheduled shouldContainExactly
            listOf(Triple(GhostedSuggestion.RECURRING_ID, GhostedSuggestion.SCHEDULE, GhostedSuggestion.REQUEST))
    }

    private fun suggestion(
        rule: String,
        created: Instant = CREATED,
    ): Task {
        val details = TaskDetails("Follow up", TaskTiming.Bucket.SOMEDAY)
        val task =
            Task.suggest(
                TaskId(UUID.randomUUID()),
                details,
                TaskOrigin.Suggested(rule, "application:x"),
                created,
            )
        fixtures.tasks[task.id] = task
        return task
    }
}
