// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolTestPorts
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import io.github.scriptibus.jofi.tasks.application.AcceptTaskSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.CompleteTaskUseCase
import io.github.scriptibus.jofi.tasks.application.CreateTaskUseCase
import io.github.scriptibus.jofi.tasks.application.GetTaskUseCase
import io.github.scriptibus.jofi.tasks.application.ListSuggestedTasksUseCase
import io.github.scriptibus.jofi.tasks.application.ListTaskGroupsUseCase
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskGroupKind
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The task tools over the real use cases with mocked repositories: arguments in, results out. */
class TaskToolsTest {
    private val tasks = mockk<TaskRepositoryPort>()
    private val changelog = ToolTestPorts.RecordingChangelog()
    private val transactions = ToolTestPorts.transactions
    private val clock = ToolTestPorts.clock

    private val taskId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val applicationId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val bucket = TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2026, 10, 1))
    private val open = Task.create(TaskId(taskId), TaskDetails("Call", bucket), TaskOrigin.Manual, at)
    private val suggestion =
        Task.suggest(TaskId(taskId), TaskDetails("Follow up", bucket), TaskOrigin.Suggested("follow-up", "a:1"), at)

    private val createTask = CreateTaskTool(CreateTaskUseCase(tasks, changelog, transactions, clock))
    private val completeTask = CompleteTaskTool(CompleteTaskUseCase(tasks, changelog, transactions, clock))
    private val acceptSuggestion =
        AcceptTaskSuggestionTool(AcceptTaskSuggestionUseCase(tasks, changelog, transactions, clock))
    private val listTasks = ListTasksTool(ListTaskGroupsUseCase(tasks, clock, ToolTestPorts.redaction))
    private val listSuggestions = ListTaskSuggestionsTool(ListSuggestedTasksUseCase(tasks, ToolTestPorts.redaction))
    private val getTask = GetTaskTool(GetTaskUseCase(tasks))

    @Test
    fun `create_task stores a chat task with timing and link, records the AI and marks the text untrusted`() {
        val added = slot<Task>()
        every { tasks.add(capture(added)) } returns TaskStoreResult.Success(Unit)

        val answer =
            createTask.call(
                call(
                    "title" to " Call back ",
                    "timeZone" to "Europe/Berlin",
                    "localDue" to "2026-10-05T10:00",
                    "link" to mapOf("type" to "APPLICATION", "id" to applicationId.toString()),
                    "notes" to "ask about pay",
                ),
            )

        added.captured.origin shouldBe TaskOrigin.Chat
        added.captured.details.title shouldBe "Call back"
        added.captured.details.link shouldBe ApplicationRef(applicationId)
        changelog.entries.single().actor shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<TaskDetailResult>()
        result.timing.dueAt shouldBe Instant.parse("2026-10-05T08:00:00Z")
        result.timing.timeZone shouldBe "Europe/Berlin"
        result.link shouldBe TaskLinkResult(TaskLinkKind.APPLICATION, applicationId)
        result.origin shouldBe TaskOriginKind.CHAT
        result.task.shouldBeInstanceOf<Untrusted<TaskWords>>().content shouldBe TaskWords("Call back", "ask about pay")
    }

    @Test
    fun `explicit nulls for the optional arguments are the same as leaving them out`() {
        every { tasks.add(any()) } returns TaskStoreResult.Success(Unit)

        val answer =
            createTask.call(
                call(
                    "title" to "T",
                    "timeZone" to "UTC",
                    "bucket" to "SOMEDAY",
                    "localDue" to null,
                    "link" to null,
                    "notes" to null,
                ),
            )

        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<TaskDetailResult>()
        result.timing.span shouldBe BucketSpan.SOMEDAY
        result.link shouldBe null
    }

    @Test
    fun `create_task names the arguments of every violation and stores nothing`() {
        val answer = createTask.call(call("title" to " ", "timeZone" to "Mars/Base", "bucket" to "TODAY"))

        answer shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The task arguments are invalid.",
                listOf(ArgumentProblem("title", "required"), ArgumentProblem("timeZone", "invalid-time-zone")),
            )
        verify(exactly = 0) { tasks.add(any()) }
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `both or neither of bucket and localDue is a timing problem, and a missing link target is a link problem`() {
        val timing = arrayOf("title" to "T", "timeZone" to "UTC")
        val both = createTask.call(call(*timing, "bucket" to "TODAY", "localDue" to "2026-10-05T10:00"))
        val neither = createTask.call(call(*timing))
        every { tasks.add(any()) } returns TaskStoreResult.LinkNotFound
        val link = mapOf("type" to "CONTACT", "id" to applicationId.toString())
        val missing = createTask.call(call(*timing, "bucket" to "TODAY", "link" to link))

        both.shouldBeInstanceOf<ToolAnswer.Error>().problems shouldContainExactly
            listOf(ArgumentProblem("bucket", "ambiguous"), ArgumentProblem("localDue", "ambiguous"))
        neither.shouldBeInstanceOf<ToolAnswer.Error>().problems shouldContainExactly
            listOf(ArgumentProblem("bucket", "required"), ArgumentProblem("localDue", "required"))
        missing.shouldBeInstanceOf<ToolAnswer.Error>().problems shouldContainExactly
            listOf(ArgumentProblem("link", "not-found"))
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `create_task refuses arguments of the wrong shape by name`() {
        listOf(
            call("title" to "T", "timeZone" to "UTC", "bucket" to "NEVER") to "bucket",
            call("title" to "T", "timeZone" to "UTC", "localDue" to "tomorrow") to "localDue",
            call("title" to "T", "timeZone" to "UTC", "link" to mapOf("type" to "TASK", "id" to "x")) to "link",
            call("title" to "T", "timeZone" to "UTC", "link" to mapOf("type" to "CONTACT")) to "link",
            call("title" to 7, "timeZone" to "UTC") to "title",
        ).forEach { (call, argument) ->
            shouldThrow<InvalidToolArgument> { createTask.call(call) }.argument shouldBe argument
        }
    }

    @Test
    fun `complete_task moves an open task to done, based on the version, and records the AI`() {
        val moved = slot<Task>()
        every { tasks.findById(TaskId(taskId)) } returns TaskStoreResult.Success(open)
        every { tasks.update(capture(moved)) } returns TaskStoreResult.Success(Unit)

        val answer = completeTask.call(call("id" to taskId.toString(), "version" to 0))

        moved.captured.state shouldBe TaskState.DONE
        changelog.entries.single().actor shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<TaskDetailResult>()
        result.status shouldBe TaskState.DONE
        result.version shouldBe 1
        result.completedAt shouldBe ToolTestPorts.clock.instant()
    }

    @Test
    fun `complete_task answers conflict, not-found and a wrong state without stored content`() {
        every { tasks.findById(TaskId(taskId)) } returns TaskStoreResult.Success(suggestion)
        every { tasks.findById(TaskId(MISSING)) } returns TaskStoreResult.NotFound

        val stale = completeTask.call(call("id" to taskId.toString(), "version" to 5))
        stale.shouldBeInstanceOf<ToolAnswer.Error>().code shouldBe "version-conflict"
        completeTask.call(call("id" to MISSING.toString(), "version" to 0)) shouldBe
            ToolAnswer.Error("not-found", "No task has this id.")
        completeTask.call(call("id" to taskId.toString(), "version" to 0)) shouldBe
            ToolAnswer.Error(
                "invalid-transition",
                "A task cannot move from SUGGESTED to DONE. Read it again to see its state.",
            )
        verify(exactly = 0) { tasks.update(any()) }
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `accept_task_suggestion opens a suggestion, keeps its origin and records the AI`() {
        val moved = slot<Task>()
        every { tasks.findById(TaskId(taskId)) } returns TaskStoreResult.Success(suggestion)
        every { tasks.update(capture(moved)) } returns TaskStoreResult.Success(Unit)

        val answer = acceptSuggestion.call(call("id" to taskId.toString(), "version" to 0))

        moved.captured.state shouldBe TaskState.OPEN
        changelog.entries.single().actor shouldBe Actor.Ai
        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<TaskDetailResult>()
        result.origin shouldBe TaskOriginKind.SUGGESTED
        result.suggestionRule shouldBe "follow-up"
    }

    @Test
    fun `accepting a dismissed or a done task, and completing a dismissed one, answers invalid-transition`() {
        val dismissed = (suggestion.apply(TaskTransition.DISMISS, at) as TaskStateChange.Changed).task
        val done = (open.apply(TaskTransition.COMPLETE, at) as TaskStateChange.Changed).task
        val other = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
        every { tasks.findById(TaskId(taskId)) } returns TaskStoreResult.Success(dismissed)
        every { tasks.findById(TaskId(other)) } returns TaskStoreResult.Success(done.copy(id = TaskId(other)))

        fun invalid(
            from: String,
            to: String,
        ) = "A task cannot move from $from to $to. Read it again to see its state."

        acceptSuggestion.call(call("id" to taskId.toString(), "version" to 1)) shouldBe
            ToolAnswer.Error("invalid-transition", invalid("DISMISSED", "OPEN"))
        acceptSuggestion.call(call("id" to other.toString(), "version" to 1)) shouldBe
            ToolAnswer.Error("invalid-transition", invalid("DONE", "OPEN"))
        completeTask.call(call("id" to taskId.toString(), "version" to 1)) shouldBe
            ToolAnswer.Error("invalid-transition", invalid("DISMISSED", "DONE"))
        changelog.entries shouldBe emptyList()
    }

    @Test
    fun `an unavailable store is reported without detail, and both write tools need their id and version`() {
        every { tasks.findById(TaskId(taskId)) } returns TaskStoreResult.StorageFailure("read")

        acceptSuggestion.call(call("id" to taskId.toString(), "version" to 0)) shouldBe
            ToolAnswer.Error("unavailable", "Tasks cannot be used now.")
        listOf(completeTask, acceptSuggestion).forEach { tool ->
            shouldThrow<InvalidToolArgument> { tool.call(call("version" to 0)) }.argument shouldBe "id"
            shouldThrow<InvalidToolArgument> { tool.call(call("id" to taskId.toString())) }.argument shouldBe "version"
        }
    }

    @Test
    fun `the read tools are read only and the write tools are not`() {
        listOf(listTasks, listSuggestions, getTask).map { it.readOnly } shouldBe listOf(true, true, true)
        listOf(createTask, completeTask, acceptSuggestion).map { it.readOnly } shouldBe listOf(false, false, false)
    }

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)

    private companion object {
        val MISSING: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
