// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolTestPorts
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import io.github.scriptibus.jofi.tasks.application.GetTaskUseCase
import io.github.scriptibus.jofi.tasks.application.ListSuggestedTasksUseCase
import io.github.scriptibus.jofi.tasks.application.ListTaskGroupsUseCase
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskGroupKind
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
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

/** The task list and read tools over the real use cases with a mocked repository (#236, ADR-0056). */
class TaskListToolsTest {
    private val tasks = mockk<TaskRepositoryPort>()
    private val clock = ToolTestPorts.clock

    private val taskId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val bucket = TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2026, 10, 1))
    private val open = Task.create(TaskId(taskId), TaskDetails("Call", bucket), TaskOrigin.Manual, at)
    private val suggestion =
        Task.suggest(TaskId(taskId), TaskDetails("Follow up", bucket), TaskOrigin.Suggested("follow-up", "a:1"), at)

    private val listTasks = ListTasksTool(ListTaskGroupsUseCase(tasks, clock, ToolTestPorts.redaction))
    private val listSuggestions = ListTaskSuggestionsTool(ListSuggestedTasksUseCase(tasks, ToolTestPorts.redaction))
    private val getTask = GetTaskTool(GetTaskUseCase(tasks))

    @Test
    fun `list_tasks groups the open tasks on the calendar of the given zone, empty groups included`() {
        val ended = TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2026, 9, 29))
        val overdue = Task.create(TaskId(taskId), TaskDetails("Old", ended), TaskOrigin.Manual, at)
        every { tasks.listByState(TaskState.OPEN) } returns TaskStoreResult.Success(listOf(overdue, open))

        val answer = listTasks.call(call("timeZone" to "Europe/Berlin"))

        val groups =
            answer
                .shouldBeInstanceOf<ToolAnswer.Result>()
                .value
                .shouldBeInstanceOf<TaskGroupsResult>()
                .groups
        groups.map { it.group } shouldBe TaskGroupKind.entries
        groups
            .first { it.group == TaskGroupKind.OVERDUE }
            .tasks
            .single()
            .task.content.title shouldBe "Old"
        groups
            .first { it.group == TaskGroupKind.TODAY }
            .tasks
            .single()
            .task.content.title shouldBe "Call"
        groups.first { it.group == TaskGroupKind.LATER }.tasks shouldBe emptyList()
    }

    @Test
    fun `list_tasks sees the same task on the calendar of the zone it is given`() {
        val evening = TaskTiming.Exact.of(java.time.LocalDateTime.of(2026, 10, 1, 20, 0), java.time.ZoneOffset.UTC)
        val exact = Task.create(TaskId(taskId), TaskDetails("Evening", requireNotNull(evening)), TaskOrigin.Manual, at)
        every { tasks.listByState(TaskState.OPEN) } returns TaskStoreResult.Success(listOf(exact))

        fun groupIn(zone: String): TaskGroupKind =
            listTasks
                .call(call("timeZone" to zone))
                .shouldBeInstanceOf<ToolAnswer.Result>()
                .value
                .shouldBeInstanceOf<TaskGroupsResult>()
                .groups
                .single { it.tasks.isNotEmpty() }
                .group

        groupIn("UTC") shouldBe TaskGroupKind.TODAY
        groupIn("Pacific/Kiritimati") shouldBe TaskGroupKind.THIS_WEEK
    }

    @Test
    fun `list_tasks refuses an unknown zone by name and reads nothing`() {
        listTasks.call(call("timeZone" to "Mars/Base")) shouldBe
            ToolAnswer.Error(
                "invalid-arguments",
                "The task arguments are invalid.",
                listOf(ArgumentProblem("timeZone", "invalid-time-zone")),
            )
        listTasks.call(call()).shouldBeInstanceOf<ToolAnswer.Error>().problems shouldContainExactly
            listOf(ArgumentProblem("timeZone", "invalid-time-zone"))
        verify(exactly = 0) { tasks.listByState(any()) }
    }

    @Test
    fun `list_task_suggestions answers the waiting suggestions with their ids and versions`() {
        every { tasks.pageByStateNewestFirst(TaskState.SUGGESTED, any()) } returns
            TaskStoreResult.Success(Paged(listOf(suggestion), PageInfo(0, 20, 1, false)))

        val answer = listSuggestions.call(call())

        val result = answer.shouldBeInstanceOf<ToolAnswer.Result>().value.shouldBeInstanceOf<TaskSuggestionsResult>()
        result.tasks.single().id shouldBe taskId
        result.tasks.single().status shouldBe TaskState.SUGGESTED
        result.tasks
            .single()
            .task.content.title shouldBe "Follow up"
    }

    @Test
    fun `the page arguments reach the use cases and the answer says where the page sits`() {
        val requested = slot<PageRequest>()
        every { tasks.pageByStateNewestFirst(TaskState.SUGGESTED, capture(requested)) } returns
            TaskStoreResult.Success(Paged(listOf(suggestion), PageInfo(2, 5, 12, true)))

        val result =
            listSuggestions
                .call(call("page" to 2, "size" to 5))
                .shouldBeInstanceOf<ToolAnswer.Result>()
                .value
                .shouldBeInstanceOf<TaskSuggestionsResult>()

        requested.captured shouldBe PageRequest(2, 5)
        listOf(result.page, result.size, result.total, result.hasMore) shouldBe listOf(2, 5, 12, true)
    }

    @Test
    fun `a page or size out of range is invalid, named by argument, and reads nothing`() {
        val expected =
            listOf(ArgumentProblem("page", "out-of-range"), ArgumentProblem("size", "out-of-range"))

        listSuggestions.call(call("page" to -1, "size" to 51)).shouldBeInstanceOf<ToolAnswer.Error>().problems shouldBe
            expected
        listTasks
            .call(call("timeZone" to "UTC", "page" to -1, "size" to 0))
            .shouldBeInstanceOf<ToolAnswer.Error>()
            .problems shouldBe expected
        shouldThrow<InvalidToolArgument> { listSuggestions.call(call("size" to "ten")) }.argument shouldBe "size"
        verify(exactly = 0) { tasks.pageByStateNewestFirst(any(), any()) }
        verify(exactly = 0) { tasks.listByState(any()) }
    }

    @Test
    fun `list entries carry an untrusted excerpt under their own keys and get_task has the whole notes`() {
        val long = "n".repeat(TextExcerpt.MAX_LENGTH + 5)
        val noted = open.copy(details = open.details.copy(notes = long))
        every { tasks.listByState(TaskState.OPEN) } returns TaskStoreResult.Success(listOf(noted))
        every { tasks.findById(noted.id) } returns TaskStoreResult.Success(noted)
        every { tasks.findById(TaskId(MISSING)) } returns TaskStoreResult.NotFound

        val listed =
            listTasks
                .call(call("timeZone" to "UTC"))
                .shouldBeInstanceOf<ToolAnswer.Result>()
                .value
                .shouldBeInstanceOf<TaskGroupsResult>()
                .groups
                .flatMap { it.tasks }
                .single()
        val full =
            getTask
                .call(call("id" to taskId.toString()))
                .shouldBeInstanceOf<ToolAnswer.Result>()
                .value
                .shouldBeInstanceOf<TaskDetailResult>()

        listed.task.content.notesExcerpt shouldBe "n".repeat(TextExcerpt.MAX_LENGTH)
        listed.task.content.notesTruncated shouldBe true
        full.task.content.notes shouldBe long
        full.version shouldBe 0
        getTask.call(call("id" to MISSING.toString())) shouldBe ToolAnswer.Error("not-found", "No task has this id.")
    }

    private fun call(vararg arguments: Pair<String, Any?>) = ToolCall(ToolArguments(mapOf(*arguments)), Actor.Ai)

    private companion object {
        val MISSING: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
