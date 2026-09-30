// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.APPLIED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.DECLINED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.DISCOVERED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.INTERVIEWING
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.OFFER
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus.REJECTED
import io.github.scriptibus.jofi.applications.domain.ApplicationStatusChanged
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.DeclineReason
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.applications.domain.StatusChangeInput
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class ChangeApplicationStatusUseCaseTest {
    private val fixtures = ApplicationFixtures()
    private val change =
        ChangeApplicationStatusUseCase(
            fixtures.repository,
            fixtures.snapshotPort,
            fixtures.eventPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val history = GetApplicationStatusHistoryUseCase(fixtures.repository)
    private val scanner = Actor.Scanner("arbeitsagentur")

    private fun move(
        application: Application,
        input: StatusChangeInput,
        actor: Actor = Actor.User,
    ): Application =
        change
            .execute(application.id, input, application.version, actor)
            .shouldBeInstanceOf<ApplicationResult.Success<Application>>()
            .value

    @Test
    fun `a move stores a new version with its history entry, changelog entry and event`() {
        val stored = fixtures.application()

        val moved = move(stored, StatusChangeInput(INTERVIEWING, "Recruiter called"), scanner)

        moved.status shouldBe INTERVIEWING
        moved.version shouldBe 1
        moved.updatedAt shouldBe NOW
        fixtures.applications[stored.id] shouldBe moved
        fixtures.history.last() shouldBe
            StatusChange(stored.id, DISCOVERED, INTERVIEWING, "Recruiter called", null, scanner, NOW)
        val entry = fixtures.entries.first()
        entry.entity shouldBe stored.id.toEntityRef()
        entry.actor shouldBe scanner
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Changed application status"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("status", "DISCOVERED", "INTERVIEWING"))
        fixtures.events shouldContainExactly
            listOf(ApplicationStatusChanged(stored.id, DISCOVERED, INTERVIEWING, scanner, NOW))
    }

    @Test
    fun `declining records the category but never the reason's text`() {
        val stored = fixtures.application()

        val declined = move(stored, StatusChangeInput(DECLINED, "Salary too low: 40k", DeclineCategory.SALARY))

        declined.declineReason shouldBe DeclineReason(DeclineCategory.SALARY, "Salary too low: 40k")
        val entry = fixtures.entries.single()
        entry.change.fieldChanges shouldContainExactly
            listOf(FieldChange("status", "DISCOVERED", "DECLINED"), FieldChange("declineReason", null, "SALARY"))
        entry.toString() shouldNotContain "40k"
        entry.change.fieldChanges.joinToString() shouldNotContain "40k"
    }

    @Test
    fun `correcting the decline reason records the field declineReason, not a status move`() {
        val declined = move(fixtures.application(), StatusChangeInput(DECLINED, "Too far", DeclineCategory.LOCATION))

        val corrected = move(declined, StatusChangeInput(DECLINED, "Salary, really", DeclineCategory.SALARY))

        corrected.version shouldBe 2
        corrected.declineReason shouldBe DeclineReason(DeclineCategory.SALARY, "Salary, really")
        val entry = fixtures.entries.last()
        entry.change.description shouldBe "Corrected decline reason"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("declineReason", "LOCATION", "SALARY"))
        fixtures.events.last() shouldBe ApplicationStatusChanged(declined.id, DECLINED, DECLINED, Actor.User, NOW)
    }

    @Test
    fun `a corrected text with the same category is a correction without a field value`() {
        val rejected =
            move(fixtures.application(), StatusChangeInput(APPLIED)).let {
                move(it, StatusChangeInput(REJECTED, null, DeclineCategory.NO_REASON_GIVEN))
            }

        move(rejected, StatusChangeInput(REJECTED, "They wrote back: no visa", DeclineCategory.NO_REASON_GIVEN))

        val entry = fixtures.entries.last()
        entry.change.description shouldBe "Corrected decline reason"
        entry.change.fieldChanges.shouldBeEmpty()
        entry.toString() shouldNotContain "visa"
        fixtures.history.last().reason shouldBe "They wrote back: no visa"
    }

    @Test
    fun `reopening clears the decline reason and records it`() {
        val declined = move(fixtures.application(), StatusChangeInput(DECLINED, null, DeclineCategory.TIMING))

        val reopened = move(declined, StatusChangeInput(ApplicationStatus.SHORTLISTED))

        reopened.declineReason shouldBe null
        fixtures.entries
            .last()
            .change.fieldChanges shouldContainExactly
            listOf(FieldChange("status", "DECLINED", "SHORTLISTED"), FieldChange("declineReason", "TIMING", null))
    }

    @Test
    fun `the same status with the same reason is a no-op`() {
        val stored = fixtures.application()

        move(stored, StatusChangeInput(DISCOVERED)) shouldBe stored

        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
        fixtures.history.size shouldBe 1
    }

    @Test
    fun `a move the matrix forbids is an invalid transition and stores nothing`() {
        val stored = fixtures.application()

        change.execute(stored.id, StatusChangeInput(ApplicationStatus.ACCEPTED), 0, Actor.User) shouldBe
            ApplicationResult.InvalidTransition(DISCOVERED, ApplicationStatus.ACCEPTED)

        fixtures.applications[stored.id] shouldBe stored
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a stale version is a conflict, checked before the input and even for a no-op`() {
        val stored = fixtures.application(version = 2)

        change.execute(stored.id, StatusChangeInput(DISCOVERED), 1, Actor.User) shouldBe
            ApplicationResult.VersionConflict
        change.execute(stored.id, StatusChangeInput(DECLINED), 1, Actor.User) shouldBe ApplicationResult.VersionConflict

        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a move that raced another change is a conflict`() {
        val stored = fixtures.application()
        fixtures.concurrentVersion = 1

        change.execute(stored.id, StatusChangeInput(APPLIED), 0, Actor.User) shouldBe ApplicationResult.VersionConflict

        fixtures.entries.shouldBeEmpty()
        fixtures.frozenAt.size shouldBe 0
    }

    @Test
    fun `declined and rejected need a category, other statuses take none`() {
        val stored = fixtures.application()

        change.execute(stored.id, StatusChangeInput(DECLINED), 0, Actor.User) shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.DECLINE_CATEGORY, ApplicationProblem.REQUIRED)),
            )
        change.execute(stored.id, StatusChangeInput(APPLIED, null, DeclineCategory.SALARY), 0, Actor.User) shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.DECLINE_CATEGORY, ApplicationProblem.NOT_APPLICABLE)),
            )
    }

    @Test
    fun `an unknown application is not found`() {
        change.execute(ApplicationId(UUID.randomUUID()), StatusChangeInput(APPLIED), 0, Actor.User) shouldBe
            ApplicationResult.NotFound
    }

    @Test
    fun `applying freezes the descriptions with one changelog entry per snapshot, by the same actor`() {
        val stored = fixtures.application()
        val snapshots = listOf(SnapshotId(UUID.randomUUID()), SnapshotId(UUID.randomUUID()))
        fixtures.freezable[stored.id] = snapshots

        move(stored, StatusChangeInput(OFFER), Actor.Ai)

        snapshots.forEach { fixtures.frozenAt[it] shouldBe NOW }
        val frozenEntries = fixtures.entries.drop(1)
        frozenEntries.map { it.entity } shouldContainExactly snapshots.map { it.toEntityRef() }
        frozenEntries.forEach {
            it.actor shouldBe Actor.Ai
            it.change.fieldChanges shouldContainExactly listOf(FieldChange("frozenAt", null, NOW.toString()))
        }
    }

    @Test
    fun `re-applying after declining keeps the first freeze`() {
        val stored = fixtures.application()
        val snapshot = SnapshotId(UUID.randomUUID())
        fixtures.freezable[stored.id] = listOf(snapshot)
        val applied = move(stored, StatusChangeInput(APPLIED))
        val entriesAfterApplying = fixtures.entries.size

        val offer = move(applied, StatusChangeInput(OFFER))
        val declined = move(offer, StatusChangeInput(DECLINED, null, DeclineCategory.OTHER_OFFER))
        move(declined, StatusChangeInput(APPLIED))

        fixtures.frozenAt[snapshot] shouldBe NOW
        fixtures.entries.drop(entriesAfterApplying).map { it.entity } shouldBe List(3) { stored.id.toEntityRef() }
    }

    @Test
    fun `moves that do not newly apply freeze nothing`() {
        val stored = fixtures.application()
        fixtures.freezable[stored.id] = listOf(SnapshotId(UUID.randomUUID()))

        move(stored, StatusChangeInput(ApplicationStatus.PREPARING))

        fixtures.frozenAt.size shouldBe 0
    }

    @Test
    fun `a failing freeze, changelog or event rolls the whole move back`() {
        val stored = fixtures.application()
        fixtures.freezable[stored.id] = listOf(SnapshotId(UUID.randomUUID()))

        fixtures.failingFreeze = true
        change.execute(stored.id, StatusChangeInput(APPLIED), 0, Actor.User) shouldBe
            ApplicationResult.StorageFailure("freeze")
        fixtures.failingFreeze = false
        fixtures.failingEvents = true
        change.execute(stored.id, StatusChangeInput(APPLIED), 0, Actor.User) shouldBe
            ApplicationResult.StorageFailure("publish event")
        fixtures.failingEvents = false
        fixtures.failingChangelog = true
        change.execute(stored.id, StatusChangeInput(APPLIED), 0, Actor.User) shouldBe
            ApplicationResult.StorageFailure("changelog")

        fixtures.applications[stored.id] shouldBe stored
        fixtures.history.size shouldBe 1
        fixtures.frozenAt.size shouldBe 0
        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `the history lists every change, oldest first`() {
        val stored = fixtures.application()
        move(stored, StatusChangeInput(APPLIED))

        history.execute(stored.id).shouldBeInstanceOf<ApplicationResult.Success<List<StatusChange>>>().value.map {
            it.from to it.to
        } shouldContainExactly listOf(null to DISCOVERED, DISCOVERED to APPLIED)
        history.execute(ApplicationId(UUID.randomUUID())) shouldBe ApplicationResult.NotFound
    }
}
