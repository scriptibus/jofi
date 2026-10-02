// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskField
import io.github.scriptibus.jofi.tasks.domain.TaskGroupKind
import io.github.scriptibus.jofi.tasks.domain.TaskGroupsPage
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskSummary
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskViolation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Paging, size limits and note excerpts of the two task lists (#236, ADR-0056). */
class TaskListPagingTest {
    private val fixtures = TaskFixtures()
    private val groups = ListTaskGroupsUseCase(fixtures.repository, TaskFixtures.CLOCK, fixtures.redaction)
    private val suggestions = ListSuggestedTasksUseCase(fixtures.repository, fixtures.redaction)
    private val utc = ZoneId.of("UTC")

    private fun groupsPage(
        page: Int,
        size: Int,
    ): TaskGroupsPage =
        groups
            .execute(utc, PageInput(page, size), NotesAudience.USER)
            .shouldBeInstanceOf<TaskResult.Success<TaskGroupsPage>>()
            .value

    private fun suggestionsPage(
        page: Int,
        size: Int,
    ): Paged<TaskSummary> =
        suggestions
            .execute(PageInput(page, size), NotesAudience.USER)
            .shouldBeInstanceOf<TaskResult.Success<Paged<TaskSummary>>>()
            .value

    private fun open(
        title: String,
        notes: String? = null,
        timing: TaskTiming = TaskTiming.Bucket.SOMEDAY,
    ): Task {
        val task =
            Task.create(
                TaskId(UUID.randomUUID()),
                TaskDetails(title, timing, null, notes),
                TaskOrigin.Manual,
                TaskFixtures.CREATED,
            )
        fixtures.tasks[task.id] = task
        return task
    }

    private fun suggest(
        title: String,
        index: Int,
        notes: String? = null,
    ): Task {
        val details = TaskDetails(title, TaskTiming.Bucket.SOMEDAY, null, notes)
        val origin = TaskOrigin.Suggested("follow-up", "application:$index")
        val task =
            Task.suggest(
                TaskId(UUID.randomUUID()),
                details,
                origin,
                TaskFixtures.CREATED.plusSeconds(index.toLong()),
            )
        fixtures.tasks[task.id] = task
        return task
    }

    @Test
    fun `120 open tasks are reached exactly once over three pages of 50, in group order`() {
        val today = TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2026, 9, 30))
        (1..60).forEach { open("someday $it") }
        (1..60).forEach { open("today $it", timing = today) }

        val pages = (0..2).map { groupsPage(it, 50) }

        val titles = pages.flatMap { page -> page.groups.flatMap { group -> group.tasks.map { it.title } } }
        titles.size shouldBe 120
        titles.toSet().size shouldBe 120
        pages.map { it.info.hasMore } shouldContainExactly listOf(true, true, false)
        pages.map { it.groups.sumOf { group -> group.tasks.size } } shouldContainExactly listOf(50, 50, 20)
        pages.forEach { page ->
            page.groups.map { it.kind } shouldContainExactly TaskGroupKind.entries
            page.info.total shouldBe 120
        }
        pages[0]
            .groups
            .first { it.kind == TaskGroupKind.TODAY }
            .tasks.size shouldBe 50
        pages[2]
            .groups
            .first { it.kind == TaskGroupKind.SOMEDAY }
            .tasks.size shouldBe 20
    }

    @Test
    fun `a page past the end is empty but still lists every group`() {
        open("only")

        val page = groupsPage(3, 50)

        page.groups.map { it.kind } shouldContainExactly TaskGroupKind.entries
        page.groups.flatMap { it.tasks } shouldBe emptyList()
        page.info.total shouldBe 1
        page.info.hasMore shouldBe false
    }

    @Test
    fun `the default is the first page of the default size`() {
        (1..PageRequest.DEFAULT_SIZE + 5).forEach { open("t$it") }

        val page =
            groups
                .execute(
                    utc,
                    PageInput(),
                    NotesAudience.USER,
                ).shouldBeInstanceOf<TaskResult.Success<TaskGroupsPage>>()
                .value

        page.groups.sumOf { it.tasks.size } shouldBe PageRequest.DEFAULT_SIZE
        page.info.hasMore shouldBe true
    }

    @Test
    fun `list entries carry an excerpt of the notes, cut and flagged, never the whole text`() {
        val long = "x".repeat(TextExcerpt.MAX_LENGTH * 3)
        open("Long", notes = long)
        open("Short", notes = "brief")
        open("None")

        val entries = groupsPage(0, 50).groups.flatMap { it.tasks }.associateBy { it.title }

        entries.getValue("Long").notesExcerpt shouldBe TextExcerpt("x".repeat(TextExcerpt.MAX_LENGTH), true)
        entries.getValue("Short").notesExcerpt shouldBe TextExcerpt("brief", false)
        entries.getValue("None").notesExcerpt shouldBe null
    }

    @Test
    fun `a page or size out of range is invalid and names what is wrong, reading nothing`() {
        fixtures.failingStore = true

        groups.execute(utc, PageInput(-1, 10), NotesAudience.USER) shouldBe
            TaskResult.Invalid(listOf(TaskViolation(TaskField.PAGE, TaskProblem.OUT_OF_RANGE)))
        groups.execute(utc, PageInput(0, PageRequest.MAX_SIZE + 1), NotesAudience.USER) shouldBe
            TaskResult.Invalid(listOf(TaskViolation(TaskField.SIZE, TaskProblem.OUT_OF_RANGE)))
        suggestions.execute(PageInput(0, 0), NotesAudience.USER) shouldBe
            TaskResult.Invalid(listOf(TaskViolation(TaskField.SIZE, TaskProblem.OUT_OF_RANGE)))
        suggestions.execute(PageInput(-2, -2), NotesAudience.USER) shouldBe
            TaskResult.Invalid(
                listOf(
                    TaskViolation(TaskField.PAGE, TaskProblem.OUT_OF_RANGE),
                    TaskViolation(TaskField.SIZE, TaskProblem.OUT_OF_RANGE),
                ),
            )
    }

    @Test
    fun `120 suggestions are reached exactly once over pages of 50, newest first`() {
        (1..120).forEach { suggest("s$it", it) }

        val pages = (0..2).map { suggestionsPage(it, 50) }

        pages.flatMap { it.items }.map { it.title } shouldContainExactly (120 downTo 1).map { "s$it" }
        pages.map { it.info.hasMore } shouldContainExactly listOf(true, true, false)
        pages.forEach { it.info.total shouldBe 120 }
    }

    @Test
    fun `suggestions carry note excerpts too, and a failing store is a storage failure`() {
        suggest("S", 1, notes = "y".repeat(TextExcerpt.MAX_LENGTH + 1))

        suggestionsPage(0, 10).items.single().notesExcerpt shouldBe
            TextExcerpt("y".repeat(TextExcerpt.MAX_LENGTH), true)
        fixtures.tasks.values
            .single()
            .state shouldBe TaskState.SUGGESTED

        fixtures.failingStore = true
        suggestions.execute(PageInput(), NotesAudience.USER) shouldBe
            TaskResult.StorageFailure("pageByStateNewestFirst")
    }

    @Test
    fun `for an AI the flagged values go out of the whole notes before the cut, so none is left half in`() {
        val phone = "0170 1234567"
        // The value straddles the cut: its first digits would be in the excerpt, the rest beyond it.
        val notes = "x".repeat(TextExcerpt.MAX_LENGTH - 5) + " " + phone + " end"
        open("Call", notes = notes)
        suggest("S", 1, notes = notes)
        fixtures.flaggedValues = setOf(FlaggedValue(phone))

        val listed = aiGroups().flatMap { it.tasks }.single()
        val suggested = aiSuggestions().single()

        listOf(listed, suggested).forEach {
            val text = it.notesExcerpt?.text.orEmpty()
            text.contains("0170") shouldBe false
            text.contains("1234") shouldBe false
            // What is left of the value is the start of the marker, never of the number.
            text.endsWith("[wit") shouldBe true
        }
        // The user sees their own notes as they are.
        val own =
            groupsPage(0, 10)
                .groups
                .flatMap { it.tasks }
                .single()
                .notesExcerpt
                ?.text
                .orEmpty()
        own.endsWith("0170") shouldBe true
    }

    @Test
    fun `for an AI the lists fail closed when the flags cannot be read`() {
        open("Call", notes = "n")
        suggest("S", 1, notes = "n")
        fixtures.flaggedValues = null

        groups.execute(utc, PageInput(), NotesAudience.AI) shouldBe TaskResult.StorageFailure("privacy flags")
        suggestions.execute(PageInput(), NotesAudience.AI) shouldBe TaskResult.StorageFailure("privacy flags")
        groups.execute(utc, PageInput(), NotesAudience.USER).shouldBeInstanceOf<TaskResult.Success<*>>()
    }

    private fun aiGroups() =
        groups
            .execute(utc, PageInput(), NotesAudience.AI)
            .shouldBeInstanceOf<TaskResult.Success<TaskGroupsPage>>()
            .value.groups

    private fun aiSuggestions() =
        suggestions
            .execute(PageInput(), NotesAudience.AI)
            .shouldBeInstanceOf<TaskResult.Success<Paged<TaskSummary>>>()
            .value.items
}
