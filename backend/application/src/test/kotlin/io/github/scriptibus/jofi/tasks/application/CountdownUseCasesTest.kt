// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.NOW
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownDetails
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.CountdownInput
import io.github.scriptibus.jofi.tasks.domain.TaskField
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskViolation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Creating, editing, listing and deleting custom countdowns (#112). */
class CountdownUseCasesTest {
    private val fixtures = CountdownFixtures()
    private val create = CreateCountdownUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val update = UpdateCountdownUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val list = ListCountdownsUseCase(fixtures.repository)
    private val delete =
        DeleteCountdownUseCase(
            fixtures.repository,
            fixtures.confirmation,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val user = ConfirmationRequester(Actor.User, "session-1")
    private val notice = CountdownInput(" Secret notice ends ", LocalDate.parse("2026-12-31"))

    @Test
    fun `creating stores the normalized countdown and records it with the actor, never the title`() {
        val created = create.execute(notice, Actor.Ai).shouldBeInstanceOf<TaskResult.Success<Countdown>>().value

        created.details shouldBe CountdownDetails("Secret notice ends", LocalDate.parse("2026-12-31"))
        created.createdAt shouldBe NOW
        fixtures.countdowns[created.id] shouldBe created
        val entry = fixtures.entries.single()
        entry.entity shouldBe created.id.toEntityRef()
        entry.actor shouldBe Actor.Ai
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Created countdown"
        entry.change.fieldChanges shouldBe listOf(FieldChange("targetDate", null, "2026-12-31"))
        entry.toString() shouldNotContain "Secret"
    }

    @Test
    fun `invalid input stores nothing`() {
        create.execute(CountdownInput(" ", LocalDate.parse("2100-01-01")), Actor.User) shouldBe
            TaskResult.Invalid(
                listOf(
                    TaskViolation(TaskField.TITLE, TaskProblem.REQUIRED),
                    TaskViolation(TaskField.TARGET_DATE, TaskProblem.OUT_OF_RANGE),
                ),
            )
        fixtures.countdowns.size shouldBe 0
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a failing changelog or store rolls the create back`() {
        fixtures.failingChangelog = true
        create.execute(notice, Actor.User) shouldBe TaskResult.StorageFailure("changelog")
        fixtures.countdowns.size shouldBe 0

        fixtures.failingChangelog = false
        fixtures.failingStore = true
        create.execute(notice, Actor.User) shouldBe TaskResult.StorageFailure("add")
    }

    @Test
    fun `updating replaces the details and records the date's values and the title's name`() {
        val stored = fixtures.countdown()
        val input = CountdownInput("Probation ends", LocalDate.parse("2027-01-31"))

        val edited = update.execute(stored.id, input, 0, Actor.User).shouldBeInstanceOf<TaskResult.Success<Countdown>>()

        edited.value.version shouldBe 1
        edited.value.updatedAt shouldBe NOW
        fixtures.countdowns[stored.id] shouldBe edited.value
        val entry = fixtures.entries.single()
        entry.actor shouldBe Actor.User
        entry.change.description shouldBe "Edited countdown; also changed: title"
        entry.change.fieldChanges shouldBe listOf(FieldChange("targetDate", "2026-12-31", "2027-01-31"))
    }

    @Test
    fun `a title change alone names only the title`() {
        val stored = fixtures.countdown()

        update.execute(stored.id, CountdownInput("Probation ends", stored.details.targetDate), 0, Actor.User)

        val entry = fixtures.entries.single()
        entry.change.description shouldBe "Edited countdown; also changed: title"
        entry.change.fieldChanges.shouldBeEmpty()
    }

    @Test
    fun `unchanged details store nothing, a stale version or unknown id fails first`() {
        val stored = fixtures.countdown()
        val same = CountdownInput(stored.details.title, stored.details.targetDate)

        update.execute(stored.id, same, 0, Actor.User) shouldBe TaskResult.Success(stored)
        fixtures.entries.shouldBeEmpty()
        update.execute(stored.id, same, 1, Actor.User) shouldBe TaskResult.VersionConflict
        update.execute(CountdownId(UUID.randomUUID()), same, 0, Actor.User) shouldBe TaskResult.CountdownNotFound
    }

    @Test
    fun `a failing changelog rolls the update back`() {
        val stored = fixtures.countdown()
        fixtures.failingChangelog = true

        update.execute(stored.id, CountdownInput("Probation ends", stored.details.targetDate), 0, Actor.User) shouldBe
            TaskResult.StorageFailure("changelog")
        fixtures.countdowns[stored.id] shouldBe stored
    }

    @Test
    fun `listing answers every countdown soonest first, a failing store is a storage failure`() {
        val later = fixtures.countdown("Later", "2027-06-01")
        val past = fixtures.countdown("Past", "2025-01-01")

        list.execute() shouldBe TaskResult.Success(listOf(past, later))

        fixtures.failingStore = true
        list.execute() shouldBe TaskResult.StorageFailure("list")
    }

    @Test
    fun `deleting asks first, naming the countdown, then deletes and records it by id only`() {
        val stored = fixtures.countdown("Secret notice ends")
        val ai = ConfirmationRequester(Actor.Ai, "chat-7")

        val required = firstStep(stored.id, ai)
        required.action.operation shouldBe Countdown.DELETE_OPERATION
        required.action.targets shouldBe listOf(stored.id.value.toString())
        required.action.effect shouldBe ConfirmationEffect("countdown", "Secret notice ends")
        fixtures.countdowns.size shouldBe 1
        fixtures.entries.shouldBeEmpty()

        delete.execute(stored.id, ai, required.token) shouldBe TaskResult.Success(Unit)

        fixtures.countdowns.size shouldBe 0
        val entry = fixtures.entries.single()
        entry.entity shouldBe stored.id.toEntityRef()
        entry.actor shouldBe Actor.Ai
        entry.change.description shouldBe "Deleted countdown"
        entry.change.fieldChanges.shouldBeEmpty()
    }

    @Test
    fun `a token is spent once, binds to its session, and a rename between the steps voids it`() {
        val stored = fixtures.countdown()
        val token = firstStep(stored.id).token
        rejected(delete.execute(stored.id, ConfirmationRequester(Actor.User, "session-2"), token))
        rejected(delete.execute(stored.id, user, token))

        val renamed = firstStep(stored.id).token
        fixtures.countdowns[stored.id] = stored.edit(stored.details.copy(title = "Other"), NOW)
        rejected(delete.execute(stored.id, user, renamed))
        fixtures.countdowns.size shouldBe 1
    }

    @Test
    fun `an unknown countdown is not found, and a failing changelog rolls the delete back`() {
        delete.execute(CountdownId(UUID.randomUUID()), user, ConfirmationToken("forged")) shouldBe
            TaskResult.CountdownNotFound

        val stored = fixtures.countdown()
        fixtures.failingChangelog = true
        delete.execute(stored.id, user, firstStep(stored.id).token) shouldBe TaskResult.StorageFailure("changelog")
        fixtures.countdowns.size shouldBe 1
    }

    private fun firstStep(
        id: CountdownId,
        requester: ConfirmationRequester = user,
    ): ConfirmationResult.Required {
        val result = delete.execute(id, requester, null).shouldBeInstanceOf<TaskResult.Unconfirmed>()
        return result.outcome.shouldBeInstanceOf<ConfirmationResult.Required>()
    }

    private fun rejected(result: TaskResult<Unit>) {
        val outcome = result.shouldBeInstanceOf<TaskResult.Unconfirmed>().outcome
        outcome.shouldBeInstanceOf<ConfirmationResult.Rejected>().reason.shouldBeInstanceOf<ConfirmationRejection>()
    }
}
