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
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.DescriptionDiff
import io.github.scriptibus.jofi.applications.domain.DescriptionInput
import io.github.scriptibus.jofi.applications.domain.DiffOperation
import io.github.scriptibus.jofi.applications.domain.DiffSegment
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SnapshotRecording
import io.github.scriptibus.jofi.applications.domain.SnapshotSummary
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class DescriptionSnapshotUseCasesTest {
    private val fixtures = ApplicationFixtures()
    private val record =
        RecordDescriptionSnapshotUseCase(
            fixtures.repository,
            fixtures.snapshotPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val list = ListDescriptionSnapshotsUseCase(fixtures.repository, fixtures.snapshotPort)
    private val get = GetDescriptionSnapshotUseCase(fixtures.repository, fixtures.snapshotPort)
    private val diff = DiffDescriptionSnapshotsUseCase(fixtures.repository, fixtures.snapshotPort)
    private val scanner = Actor.Scanner("arbeitsagentur")

    private fun record(
        application: Application,
        source: SourceId,
        text: String,
        actor: Actor = Actor.User,
    ): SnapshotRecording =
        record
            .execute(application.id, source, DescriptionInput(text, SnapshotReason.MANUAL), actor)
            .shouldBeInstanceOf<ApplicationResult.Success<SnapshotRecording>>()
            .value

    @Test
    fun `a new text is a new version with a changelog entry, the same text again stores nothing`() {
        val application = fixtures.application()
        val source = fixtures.source(application).id

        val first = record(application, source, "Kotlin\r\nBerlin ", scanner) as SnapshotRecording.Added
        val again = record(application, source, "Kotlin\nBerlin")
        val changed = record(application, source, "Kotlin\nHamburg").shouldBeInstanceOf<SnapshotRecording.Added>()

        again shouldBe SnapshotRecording.Unchanged(first.snapshot)
        first.snapshot.text.value shouldBe "Kotlin\nBerlin"
        first.snapshot.capturedAt shouldBe NOW
        first.snapshot.frozen shouldBe false
        fixtures.descriptions shouldContainExactly listOf(first.snapshot, changed.snapshot)
        fixtures.entries.map { it.entity to it.actor } shouldContainExactly
            listOf(first.snapshot.id.toEntityRef() to scanner, changed.snapshot.id.toEntityRef() to Actor.User)
        fixtures.entries
            .first()
            .change.fieldChanges shouldContainExactly
            listOf(
                FieldChange("source", null, source.value.toString()),
                FieldChange("reason", null, "MANUAL"),
                FieldChange("contentHash", null, first.snapshot.contentHash.hex),
            )
        fixtures.entries.joinToString() shouldNotContain "Berlin"
    }

    @Test
    fun `a source's first text after applying is frozen at once, a later change is not`() {
        val application = fixtures.application().copy(status = ApplicationStatus.APPLIED)
        fixtures.applications[application.id] = application
        val source = fixtures.source(application).id

        val first = record(application, source, "Kotlin").snapshot
        val later = record(application, source, "Kotlin, Remote").snapshot

        first.frozenAt shouldBe NOW
        later.frozen shouldBe false
        fixtures.entries
            .first()
            .change.fieldChanges
            .last() shouldBe FieldChange("frozenAt", null, NOW.toString())
    }

    @Test
    fun `an unknown application, a source of another one and an invalid text are refused, nothing stored`() {
        val application = fixtures.application()
        val source = fixtures.source(application).id
        val foreign = fixtures.source(fixtures.application()).id
        val input = DescriptionInput("Kotlin", SnapshotReason.MANUAL)

        record.execute(ApplicationId(UUID.randomUUID()), source, input, Actor.User) shouldBe ApplicationResult.NotFound
        record.execute(application.id, foreign, input, Actor.User) shouldBe ApplicationResult.SourceNotFound
        record.execute(application.id, source, DescriptionInput(" \n", SnapshotReason.MANUAL), Actor.User) shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.DESCRIPTION, ApplicationProblem.REQUIRED)),
            )
        fixtures.descriptions.shouldBeEmpty()
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a failing store or changelog stores nothing`() {
        val application = fixtures.application()
        val source = fixtures.source(application).id
        val input = DescriptionInput("Kotlin", SnapshotReason.MANUAL)

        fixtures.failingChangelog = true
        record.execute(application.id, source, input, Actor.User) shouldBe ApplicationResult.StorageFailure("changelog")
        fixtures.failingChangelog = false
        fixtures.failingStore = true
        record.execute(application.id, source, input, Actor.User) shouldBe
            ApplicationResult.StorageFailure("add snapshot")

        fixtures.descriptions.shouldBeEmpty()
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `versions are listed per source, oldest first, only for the application's own sources`() {
        val application = fixtures.application()
        val source = fixtures.source(application).id
        val other = fixtures.source(fixtures.applications.getValue(application.id)).id
        val first = record(application, source, "v1").snapshot
        val second = record(application, source, "v2").snapshot
        record(application, other, "elsewhere")

        list.execute(application.id, source) shouldBe
            ApplicationResult.Success(listOf(first.summary(), second.summary()))
        list.execute(application.id, SourceId(UUID.randomUUID())) shouldBe ApplicationResult.SourceNotFound
        list.execute(ApplicationId(UUID.randomUUID()), source) shouldBe ApplicationResult.NotFound
        list.execute(fixtures.application().id, source) shouldBe ApplicationResult.SourceNotFound
        list.execute(application.id, fixtures.source(fixtures.applications.getValue(application.id)).id) shouldBe
            ApplicationResult.Success(emptyList<SnapshotSummary>())
    }

    @Test
    fun `one version is read with its text, only through its own application`() {
        val application = fixtures.application()
        val snapshot = record(application, fixtures.source(application).id, "Kotlin").snapshot
        val stranger = fixtures.application()

        get.execute(application.id, snapshot.id) shouldBe ApplicationResult.Success(snapshot)
        get.execute(stranger.id, snapshot.id) shouldBe ApplicationResult.SnapshotNotFound
        get.execute(application.id, SnapshotId(UUID.randomUUID())) shouldBe ApplicationResult.SnapshotNotFound
        get.execute(ApplicationId(UUID.randomUUID()), snapshot.id) shouldBe ApplicationResult.NotFound
    }

    @Test
    fun `the diff compares two versions of the application, also of two sources`() {
        val application = fixtures.application()
        val old = record(application, fixtures.source(application).id, "Kotlin\nBerlin").snapshot
        val current = fixtures.applications.getValue(application.id)
        val new = record(current, fixtures.source(current).id, "Kotlin\nHamburg").snapshot

        diff.execute(application.id, old.id, new.id) shouldBe
            ApplicationResult.Success(
                DescriptionDiff(
                    old.id,
                    new.id,
                    listOf(
                        DiffSegment(DiffOperation.UNCHANGED, "Kotlin\n"),
                        DiffSegment(DiffOperation.REMOVED, "Berlin"),
                        DiffSegment(DiffOperation.ADDED, "Hamburg"),
                    ),
                ),
            )
        diff.execute(application.id, old.id, SnapshotId(UUID.randomUUID())) shouldBe ApplicationResult.SnapshotNotFound
        diff.execute(fixtures.application().id, old.id, new.id) shouldBe ApplicationResult.SnapshotNotFound
        diff.execute(ApplicationId(UUID.randomUUID()), old.id, new.id) shouldBe ApplicationResult.NotFound
    }
}
