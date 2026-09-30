// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.ACME
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.OfferInput
import io.github.scriptibus.jofi.applications.domain.PayBandInput
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySourceKind
import io.github.scriptibus.jofi.applications.domain.Seniority
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class ApplicationUseCasesTest {
    private val fixtures = ApplicationFixtures()
    private val create = CreateApplicationUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val update = UpdateApplicationUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val get = GetApplicationUseCase(fixtures.repository)
    private val unread =
        SetApplicationUnreadUseCase(fixtures.repository, fixtures.changelog, fixtures.transactions, CLOCK)
    private val scanner = Actor.Scanner("arbeitsagentur")

    private val private =
        ApplicationInput(
            title = "Backend Engineer",
            company = ACME,
            portalNotes = "Password hint: blue",
            payBand = PayBandInput(BigDecimal("70000"), null, "eur", PayPeriod.YEAR, PaySourceKind.POSTING),
            offer = OfferInput(bonus = "10 % bonus"),
        )

    @Test
    fun `creating stores the application in the initial status with its first history entry and changelog entry`() {
        val created =
            create
                .execute(ApplicationInput("  Backend  Engineer ", ACME, seniority = Seniority.SENIOR), Actor.Ai)
                .shouldBeInstanceOf<ApplicationResult.Success<Application>>()
                .value

        created.details.title shouldBe "Backend  Engineer"
        created.status shouldBe ApplicationStatus.INITIAL
        created.version shouldBe Application.INITIAL_VERSION
        created.createdAt shouldBe NOW
        fixtures.applications[created.id] shouldBe created
        fixtures.history shouldContainExactly listOf(StatusChange.initial(created, Actor.Ai))
        val entry = fixtures.entries.single()
        entry.entity shouldBe created.id.toEntityRef()
        entry.actor shouldBe Actor.Ai
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Created application"
        entry.change.fieldChanges shouldContainExactly
            listOf(
                FieldChange("title", null, "Backend  Engineer"),
                FieldChange("company", null, ACME.value.toString()),
                FieldChange("seniority", null, "SENIOR"),
            )
    }

    @Test
    fun `creating names notes, pay and offer in the changelog but never records their values`() {
        create.execute(private, Actor.User).shouldBeInstanceOf<ApplicationResult.Success<Application>>()

        val entry = fixtures.entries.single()
        entry.change.description shouldBe "Created application; also changed: portal notes, pay band, offer"
        entry.change.fieldChanges.map { it.field } shouldContainExactly listOf("title", "company")
        entry.toString() shouldNotContain "blue"
        entry.change.fieldChanges.joinToString().let {
            it shouldNotContain "70000"
            it shouldNotContain "bonus"
        }
    }

    @Test
    fun `text is normalized to NFC`() {
        val decomposed = "Café Manager"

        val created =
            create
                .execute(ApplicationInput(decomposed, ACME), Actor.User)
                .shouldBeInstanceOf<ApplicationResult.Success<Application>>()
                .value

        created.details.title shouldBe "Café Manager"
    }

    @Test
    fun `invalid input and an unknown company store nothing`() {
        create.execute(ApplicationInput(" ", ACME), Actor.User) shouldBe
            ApplicationResult.Invalid(listOf(ApplicationViolation(ApplicationField.TITLE, ApplicationProblem.REQUIRED)))

        create.execute(ApplicationInput("Backend Engineer", CompanyRef(UUID.randomUUID())), Actor.User) shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.COMPANY, ApplicationProblem.NOT_FOUND)),
            )

        fixtures.applications.size shouldBe 0
        fixtures.history.shouldBeEmpty()
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a changelog that cannot record rolls the creation back`() {
        fixtures.failingChangelog = true

        create.execute(ApplicationInput("Backend Engineer", ACME), Actor.User) shouldBe
            ApplicationResult.StorageFailure("changelog")

        fixtures.applications.size shouldBe 0
        fixtures.history.shouldBeEmpty()
    }

    @Test
    fun `a store that cannot answer is a storage failure`() {
        fixtures.failingStore = true

        create.execute(ApplicationInput("Backend Engineer", ACME), Actor.User) shouldBe
            ApplicationResult.StorageFailure("add")
    }

    @Test
    fun `updating replaces the details as a new version and records the changed fields`() {
        val stored = fixtures.application(unread = true)

        val edited =
            update
                .execute(stored.id, ApplicationInput("Staff Engineer", ACME, location = "Berlin"), 0, scanner)
                .shouldBeInstanceOf<ApplicationResult.Success<Application>>()
                .value

        edited.details.title shouldBe "Staff Engineer"
        edited.version shouldBe 1
        edited.updatedAt shouldBe NOW
        edited.unread shouldBe true
        fixtures.applications[stored.id] shouldBe edited
        val entry = fixtures.entries.single()
        entry.actor shouldBe scanner
        entry.change.description shouldBe "Edited application"
        entry.change.fieldChanges shouldContainExactly
            listOf(FieldChange("title", "Backend Engineer", "Staff Engineer"), FieldChange("location", null, "Berlin"))
    }

    @Test
    fun `changed notes are named, not recorded`() {
        val stored = fixtures.application()

        update.execute(stored.id, private, 0, Actor.User).shouldBeInstanceOf<ApplicationResult.Success<Application>>()

        val entry = fixtures.entries.single()
        entry.change.description shouldBe "Edited application; also changed: portal notes, pay band, offer"
        entry.change.fieldChanges.shouldBeEmpty()
    }

    @Test
    fun `a stale version is a conflict, checked before the input and even for a no-op`() {
        val stored = fixtures.application(version = 2)

        update.execute(stored.id, ApplicationInput("Backend Engineer", ACME), 1, Actor.User) shouldBe
            ApplicationResult.VersionConflict
        update.execute(stored.id, ApplicationInput(" ", ACME), 1, Actor.User) shouldBe ApplicationResult.VersionConflict

        fixtures.applications[stored.id] shouldBe stored
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `an edit that raced another one is a conflict and records nothing`() {
        val stored = fixtures.application()
        fixtures.concurrentVersion = 1

        update.execute(stored.id, ApplicationInput("Staff Engineer", ACME), 0, Actor.User) shouldBe
            ApplicationResult.VersionConflict

        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `unchanged details keep the version and write no entry`() {
        val stored = fixtures.application()

        update.execute(stored.id, ApplicationInput(" Backend Engineer ", ACME), 0, Actor.User) shouldBe
            ApplicationResult.Success(stored)

        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `updating an unknown application or to an unknown company changes nothing`() {
        val stored = fixtures.application()

        update.execute(ApplicationId(UUID.randomUUID()), ApplicationInput("X", ACME), 0, Actor.User) shouldBe
            ApplicationResult.NotFound
        update.execute(stored.id, ApplicationInput("X", CompanyRef(UUID.randomUUID())), 0, Actor.User) shouldBe
            ApplicationResult.Invalid(
                listOf(ApplicationViolation(ApplicationField.COMPANY, ApplicationProblem.NOT_FOUND)),
            )

        fixtures.applications[stored.id] shouldBe stored
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `reading answers the stored application or not found`() {
        val stored = fixtures.application()

        get.execute(stored.id) shouldBe ApplicationResult.Success(stored)
        get.execute(ApplicationId(UUID.randomUUID())) shouldBe ApplicationResult.NotFound
    }

    @Test
    fun `marking read keeps version and update time and records the flag`() {
        val stored = fixtures.application(unread = true)

        val read =
            unread
                .execute(stored.id, false, Actor.User)
                .shouldBeInstanceOf<ApplicationResult.Success<Application>>()
                .value

        read shouldBe stored.copy(unread = false)
        fixtures.applications[stored.id] shouldBe read
        val entry = fixtures.entries.single()
        entry.change.description shouldBe "Marked application read"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("unread", "true", "false"))
        entry.occurredAt shouldBe NOW
    }

    @Test
    fun `setting the flag it has is a no-op, an unknown application is not found`() {
        val stored = fixtures.application(unread = false)

        unread.execute(stored.id, false, Actor.User) shouldBe ApplicationResult.Success(stored)
        unread.execute(ApplicationId(UUID.randomUUID()), true, Actor.User) shouldBe ApplicationResult.NotFound

        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `marking unread rolls back when the changelog cannot record it`() {
        val stored = fixtures.application(unread = false)
        fixtures.failingChangelog = true

        unread.execute(stored.id, true, Actor.User) shouldBe ApplicationResult.StorageFailure("changelog")

        fixtures.applications[stored.id] shouldBe stored
    }
}
