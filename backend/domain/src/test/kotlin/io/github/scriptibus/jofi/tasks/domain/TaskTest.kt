// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID

class TaskTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val later = at.plusSeconds(60)
    private val id = TaskId(UUID.fromString("00000000-0000-0000-0000-000000000011"))
    private val details =
        TaskDetails("Secret title", TaskTiming.Bucket.SOMEDAY, ContactRef(UUID.randomUUID()), "Secret notes")
    private val suggestion = TaskOrigin.Suggested("follow-up", "application:00000000-0000-0000-0000-0000000000a1")
    private val open = Task.create(id, details, TaskOrigin.Manual, at)
    private val suggested = Task.suggest(id, details, suggestion, at)

    @Test
    fun `changelog entries refer to it as a task`() {
        id.toEntityRef() shouldBe EntityRef("task", id.value.toString())
    }

    @Test
    fun `a created task is open, a suggestion waits`() {
        open shouldBe Task(id, details, TaskOrigin.Manual, TaskState.OPEN, null, 0, at, at)
        Task.create(id, details, TaskOrigin.Chat, at).origin shouldBe TaskOrigin.Chat
        suggested.state shouldBe TaskState.SUGGESTED
        suggested.origin shouldBe suggestion
    }

    @Test
    fun `an edit is a new version, an unchanged one is the same task`() {
        open.edit(details, later) shouldBe open
        open.edit(details.copy(title = "Other"), later) shouldBe
            open.copy(details = details.copy(title = "Other"), version = 1, updatedAt = later)
    }

    @Test
    fun `completing and reopening set and clear the completion time`() {
        val done = open.apply(TaskTransition.COMPLETE, later).shouldBeInstanceOf<TaskStateChange.Changed>().task

        done shouldBe open.copy(state = TaskState.DONE, completedAt = later, version = 1, updatedAt = later)
        done
            .apply(
                TaskTransition.REOPEN,
                later.plusSeconds(1),
            ).shouldBeInstanceOf<TaskStateChange.Changed>()
            .task shouldBe
            done.copy(state = TaskState.OPEN, completedAt = null, version = 2, updatedAt = later.plusSeconds(1))
        done.apply(TaskTransition.COMPLETE, later) shouldBe TaskStateChange.Unchanged
        open.apply(TaskTransition.REOPEN, later) shouldBe TaskStateChange.Unchanged
    }

    @Test
    fun `a suggestion is accepted or dismissed, and a dismissed one stays so`() {
        val accepted = suggested.apply(TaskTransition.ACCEPT, later).shouldBeInstanceOf<TaskStateChange.Changed>().task
        val dismissed =
            suggested.apply(TaskTransition.DISMISS, later).shouldBeInstanceOf<TaskStateChange.Changed>().task

        accepted.state shouldBe TaskState.OPEN
        accepted.origin shouldBe suggestion
        dismissed.state shouldBe TaskState.DISMISSED
        TaskTransition.entries.filter { it != TaskTransition.DISMISS }.forEach {
            dismissed.apply(it, later) shouldBe TaskStateChange.NotAllowed(TaskState.DISMISSED, it.to)
        }
    }

    @Test
    fun `each transition starts from exactly one state, so no operation does another's job`() {
        val done = (open.apply(TaskTransition.COMPLETE, later) as TaskStateChange.Changed).task

        suggested.apply(TaskTransition.REOPEN, later) shouldBe
            TaskStateChange.NotAllowed(TaskState.SUGGESTED, TaskState.OPEN)
        suggested.apply(TaskTransition.COMPLETE, later) shouldBe
            TaskStateChange.NotAllowed(TaskState.SUGGESTED, TaskState.DONE)
        done.apply(TaskTransition.ACCEPT, later) shouldBe TaskStateChange.NotAllowed(TaskState.DONE, TaskState.OPEN)
        done.apply(TaskTransition.DISMISS, later) shouldBe
            TaskStateChange.NotAllowed(TaskState.DONE, TaskState.DISMISSED)
        open.apply(TaskTransition.DISMISS, later) shouldBe
            TaskStateChange.NotAllowed(TaskState.OPEN, TaskState.DISMISSED)
        TaskTransition.entries.map { it.from to it.to }.toSet() shouldBe
            setOf(
                TaskState.SUGGESTED to TaskState.OPEN,
                TaskState.SUGGESTED to TaskState.DISMISSED,
                TaskState.OPEN to TaskState.DONE,
                TaskState.DONE to TaskState.OPEN,
            )
    }

    @ParameterizedTest
    @EnumSource(TaskState::class, names = ["SUGGESTED", "DISMISSED"])
    fun `only a suggestion is suggested or dismissed`(state: TaskState) {
        shouldThrow<IllegalArgumentException> { open.copy(state = state) }
        suggested.copy(state = state).state shouldBe state
    }

    @Test
    fun `invariants hold`() {
        shouldThrow<IllegalArgumentException> { open.copy(version = -1) }
        shouldThrow<IllegalArgumentException> { open.copy(updatedAt = at.minusSeconds(1)) }
        shouldThrow<IllegalArgumentException> { open.copy(completedAt = later) }
        shouldThrow<IllegalArgumentException> { open.copy(state = TaskState.DONE) }
        shouldThrow<IllegalArgumentException> { details.copy(title = " untrimmed") }
        shouldThrow<IllegalArgumentException> { details.copy(title = "x".repeat(TaskDetails.MAX_TITLE_LENGTH + 1)) }
        shouldThrow<IllegalArgumentException> { details.copy(notes = "") }
        shouldThrow<IllegalArgumentException> { details.copy(notes = "a\u0000b") }
    }

    @Test
    fun `a suggestion has a kebab-case rule and a key without whitespace`() {
        listOf("", "Follow-up", "follow_up", "-follow", "follow--up", "x".repeat(65)).forEach {
            shouldThrow<IllegalArgumentException> { TaskOrigin.Suggested(it, "key") }
        }
        listOf("", "a b", "a b", "a\u0000b", "x".repeat(TaskOrigin.Suggested.MAX_KEY_LENGTH + 1)).forEach {
            shouldThrow<IllegalArgumentException> { TaskOrigin.Suggested("follow-up", it) }
        }
        TaskOrigin.Suggested("x".repeat(TaskOrigin.Suggested.MAX_RULE_LENGTH), "ü".repeat(200)).rule.length shouldBe 64
        TaskOrigin.Suggested("interview-prep-2", "interview:1").key shouldBe "interview:1"
    }

    @Test
    fun `nothing personal is printed`() {
        listOf(open, details, TaskGroup(TaskGroupKind.TODAY, listOf(open))).forEach {
            it.toString() shouldNotContain "Secret"
            it.toString() shouldNotContain (details.link?.value.toString())
        }
    }
}
