// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.NOW
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class DeleteTaskUseCaseTest {
    private val fixtures = TaskFixtures()
    private val delete =
        DeleteTaskUseCase(fixtures.repository, fixtures.confirmation, fixtures.changelog, fixtures.transactions, CLOCK)
    private val user = ConfirmationRequester(Actor.User, "session-1")

    private fun firstStep(
        id: TaskId,
        requester: ConfirmationRequester = user,
    ): ConfirmationResult.Required {
        val result = delete.execute(id, requester, null).shouldBeInstanceOf<TaskResult.Unconfirmed>()
        return result.outcome.shouldBeInstanceOf<ConfirmationResult.Required>()
    }

    @Test
    fun `the first call only asks, naming the task`() {
        val task = fixtures.task("Call Erika back")

        val required = firstStep(task.id)

        required.action.operation shouldBe Task.DELETE_OPERATION
        required.action.targets shouldBe listOf(task.id.value.toString())
        required.action.effect shouldBe ConfirmationEffect("task", "Call Erika back")
        fixtures.tasks.size shouldBe 1
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `the confirmed repeat deletes and records it by id only`() {
        val task = fixtures.task("Call Erika back")
        val ai = ConfirmationRequester(Actor.Ai, "chat-7")

        delete.execute(task.id, ai, firstStep(task.id, ai).token) shouldBe TaskResult.Success(Unit)

        fixtures.tasks.size shouldBe 0
        val entry = fixtures.entries.single()
        entry.entity shouldBe task.id.toEntityRef()
        entry.actor shouldBe Actor.Ai
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Deleted task"
        entry.change.fieldChanges.shouldBeEmpty()
    }

    @Test
    fun `a token is spent once and binds to its session`() {
        val task = fixtures.task()
        val token = firstStep(task.id).token

        rejected(delete.execute(task.id, ConfirmationRequester(Actor.User, "session-2"), token))
        rejected(delete.execute(task.id, user, token))
        fixtures.tasks.size shouldBe 1
    }

    @Test
    fun `renaming the task between the steps voids the token`() {
        val task = fixtures.task("Call back")
        val token = firstStep(task.id).token
        fixtures.tasks[task.id] = task.edit(TaskDetails("Write back", TaskTiming.Bucket.SOMEDAY), NOW)

        rejected(delete.execute(task.id, user, token))
        fixtures.tasks.keys shouldContainExactly listOf(task.id)
    }

    @Test
    fun `an unknown task is not found, before any token`() {
        delete.execute(TaskId(UUID.randomUUID()), user, null) shouldBe TaskResult.NotFound
        delete.execute(TaskId(UUID.randomUUID()), user, ConfirmationToken("forged")) shouldBe TaskResult.NotFound
    }

    @Test
    fun `a failing changelog rolls the delete back`() {
        val task = fixtures.task()
        fixtures.failingChangelog = true

        delete.execute(task.id, user, firstStep(task.id).token) shouldBe TaskResult.StorageFailure("changelog")
        fixtures.tasks.size shouldBe 1
    }

    private fun rejected(result: TaskResult<Unit>) {
        val outcome = result.shouldBeInstanceOf<TaskResult.Unconfirmed>().outcome
        outcome.shouldBeInstanceOf<ConfirmationResult.Rejected>().reason.shouldBeInstanceOf<ConfirmationRejection>()
    }
}
