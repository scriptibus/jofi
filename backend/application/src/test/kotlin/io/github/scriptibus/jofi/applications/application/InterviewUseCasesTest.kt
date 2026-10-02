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
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewInput
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewRescheduled
import io.github.scriptibus.jofi.applications.domain.InterviewScheduled
import io.github.scriptibus.jofi.applications.domain.InterviewSummary
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class InterviewUseCasesTest {
    private val fixtures = ApplicationFixtures()
    private val log =
        LogInterviewUseCase(
            fixtures.repository,
            fixtures.interviewPort,
            fixtures.eventPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val update =
        UpdateInterviewUseCase(
            fixtures.repository,
            fixtures.interviewPort,
            fixtures.eventPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val get = GetInterviewUseCase(fixtures.repository, fixtures.interviewPort)
    private val list = ListInterviewsUseCase(fixtures.repository, fixtures.interviewPort)
    private val erika = ContactRef(UUID.randomUUID()).also { fixtures.contacts += it }
    private val max = ContactRef(UUID.randomUUID()).also { fixtures.contacts += it }
    private val application: Application = fixtures.application()

    private val phoneScreen =
        InterviewInput(
            type = InterviewType.PHONE_SCREEN,
            localStart = LocalDateTime.parse("2026-10-05T10:00"),
            timeZone = "Europe/Berlin",
            participants = setOf(erika, max),
            preparationNotes = "Ask about the team",
        )

    private fun logged(
        input: InterviewInput = phoneScreen,
        actor: Actor = Actor.User,
    ): Interview =
        log
            .execute(application.id, input, actor)
            .shouldBeInstanceOf<ApplicationResult.Success<Interview>>()
            .value

    @Test
    fun `logging stores the interview with its instant, keeps the application and announces it`() {
        val interview = logged(actor = Actor.Ai)

        interview.details.time.startsAt shouldBe Instant.parse("2026-10-05T08:00:00Z")
        interview.details.participants shouldBe setOf(erika, max)
        interview.version shouldBe Interview.INITIAL_VERSION
        interview.createdAt shouldBe NOW
        fixtures.interviews[interview.id] shouldBe interview
        fixtures.applications[application.id] shouldBe application
        fixtures.events shouldContainExactly
            listOf(
                InterviewScheduled(
                    interview.id,
                    application.id,
                    InterviewType.PHONE_SCREEN,
                    interview.details.time,
                    Actor.Ai,
                    NOW,
                ),
            )
    }

    @Test
    fun `logging records type, start, zone and application, and only names notes and participants`() {
        val interview = logged(actor = Actor.Ai)

        val entry = fixtures.entries.single()
        entry.entity shouldBe interview.id.toEntityRef()
        entry.actor shouldBe Actor.Ai
        entry.occurredAt shouldBe NOW
        entry.change.description shouldBe "Logged interview; also changed: participants, preparation notes"
        entry.change.fieldChanges shouldContainExactly
            listOf(
                FieldChange("application", null, application.id.value.toString()),
                FieldChange("type", null, "PHONE_SCREEN"),
                FieldChange("startsAt", null, "2026-10-05T08:00:00Z"),
                FieldChange("timeZone", null, "Europe/Berlin"),
            )
        entry.toString().let {
            it shouldNotContain "Ask about the team"
            it shouldNotContain erika.value.toString()
        }
    }

    @Test
    fun `a time a clock change skips moves forward, one it repeats takes the earlier offset`() {
        val gap = logged(phoneScreen.copy(localStart = LocalDateTime.parse("2027-03-28T02:30")))
        val overlap = logged(phoneScreen.copy(localStart = LocalDateTime.parse("2026-10-25T02:30")))

        gap.details.time.localStart shouldBe LocalDateTime.parse("2027-03-28T03:30")
        overlap.details.time.startsAt shouldBe Instant.parse("2026-10-25T00:30:00Z")
    }

    @Test
    fun `an unknown application, invalid input and unknown participants store nothing`() {
        log.execute(ApplicationId(UUID.randomUUID()), phoneScreen, Actor.User) shouldBe ApplicationResult.NotFound
        log.execute(application.id, phoneScreen.copy(timeZone = "Mars/Olympus"), Actor.User) shouldBe
            invalid(ApplicationField.TIME_ZONE, ApplicationProblem.INVALID_TIME_ZONE)
        log.execute(
            application.id,
            phoneScreen.copy(participants = setOf(ContactRef(UUID.randomUUID()))),
            Actor.User,
        ) shouldBe
            invalid(ApplicationField.PARTICIPANTS, ApplicationProblem.NOT_FOUND)

        fixtures.interviews.size shouldBe 0
        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `a failing changelog or event rolls the log back`() {
        fixtures.failingChangelog = true
        log.execute(application.id, phoneScreen, Actor.User) shouldBe ApplicationResult.StorageFailure("changelog")

        fixtures.failingChangelog = false
        fixtures.failingEvents = true
        log.execute(application.id, phoneScreen, Actor.User) shouldBe ApplicationResult.StorageFailure("publish event")

        fixtures.interviews.size shouldBe 0
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `rescheduling stores a new version, records the move and announces it`() {
        val interview = logged()
        fixtures.entries.clear()
        fixtures.events.clear()
        val moved = phoneScreen.copy(localStart = LocalDateTime.parse("2026-10-06T14:30"), timeZone = "Europe/London")

        val edited = updated(interview, moved, Actor.Scanner("mail"))

        edited.version shouldBe 1
        edited.updatedAt shouldBe NOW
        edited.details.time.startsAt shouldBe Instant.parse("2026-10-06T13:30:00Z")
        fixtures.interviews[interview.id] shouldBe edited
        val entry = fixtures.entries.single()
        entry.actor shouldBe Actor.Scanner("mail")
        entry.change.description shouldBe "Edited interview"
        entry.change.fieldChanges shouldContainExactly
            listOf(
                FieldChange("startsAt", "2026-10-05T08:00:00Z", "2026-10-06T13:30:00Z"),
                FieldChange("timeZone", "Europe/Berlin", "Europe/London"),
            )
        fixtures.events shouldContainExactly
            listOf(
                InterviewRescheduled(
                    interview.id,
                    application.id,
                    interview.details.time,
                    edited.details.time,
                    Actor.Scanner("mail"),
                    NOW,
                ),
            )
    }

    @Test
    fun `notes and outcome afterwards are no reschedule, and notes and participants are only named`() {
        val interview = logged()
        fixtures.entries.clear()
        fixtures.events.clear()
        val afterwards =
            phoneScreen.copy(participants = setOf(erika), notes = "Went well", outcome = InterviewOutcome.PASSED)

        val edited = updated(interview, afterwards)

        edited.details.notes shouldBe "Went well"
        val entry = fixtures.entries.single()
        entry.change.description shouldBe "Edited interview; also changed: participants, notes"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("outcome", null, "PASSED"))
        entry.toString() shouldNotContain "Went well"
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `unchanged details keep the version, record nothing and announce nothing`() {
        val interview = logged()
        fixtures.entries.clear()
        fixtures.events.clear()

        updated(interview, phoneScreen.copy(preparationNotes = " Ask about the team ")) shouldBe interview

        fixtures.entries.shouldBeEmpty()
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `a stale version is a conflict, checked before the input and even for a no-op`() {
        val interview = logged()
        fixtures.entries.clear()

        update.execute(application.id, interview.id, phoneScreen, 1, Actor.User) shouldBe
            ApplicationResult.VersionConflict
        update.execute(application.id, interview.id, phoneScreen.copy(timeZone = ""), 1, Actor.User) shouldBe
            ApplicationResult.VersionConflict
        fixtures.concurrentInterviewVersion = 1
        update.execute(application.id, interview.id, phoneScreen.copy(notes = "x"), 0, Actor.User) shouldBe
            ApplicationResult.VersionConflict

        fixtures.interviews[interview.id] shouldBe interview
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `an edit naming an unknown participant stores nothing`() {
        val interview = logged()
        val stranger = phoneScreen.copy(participants = setOf(ContactRef(UUID.randomUUID())))

        update.execute(application.id, interview.id, stranger, 0, Actor.User) shouldBe
            invalid(ApplicationField.PARTICIPANTS, ApplicationProblem.NOT_FOUND)

        fixtures.interviews[interview.id] shouldBe interview
    }

    private fun listed(application: ApplicationId) =
        list
            .execute(application, PageInput(), SortDirection.ASCENDING)
            .shouldBeInstanceOf<ApplicationResult.Success<Paged<InterviewSummary>>>()
            .value

    @Test
    fun `reading and listing tell an unknown application from an interview it does not have`() {
        val later = logged(phoneScreen.copy(localStart = LocalDateTime.parse("2026-10-09T09:00")))
        val sooner = logged()
        val other = fixtures.application("Staff Engineer")
        val unknown = ApplicationId(UUID.randomUUID())

        get.execute(application.id, sooner.id) shouldBe ApplicationResult.Success(sooner)
        listed(application.id).items shouldBe listOf(sooner, later).map(InterviewSummary::of)
        listed(other.id).items shouldBe emptyList()
        get.execute(other.id, sooner.id) shouldBe ApplicationResult.InterviewNotFound
        get.execute(unknown, sooner.id) shouldBe ApplicationResult.NotFound
        list.execute(unknown, PageInput(), SortDirection.ASCENDING) shouldBe ApplicationResult.NotFound
        update.execute(other.id, sooner.id, phoneScreen, 0, Actor.User) shouldBe ApplicationResult.InterviewNotFound
        update.execute(application.id, InterviewId(UUID.randomUUID()), phoneScreen, 0, Actor.User) shouldBe
            ApplicationResult.InterviewNotFound
    }

    private fun updated(
        interview: Interview,
        input: InterviewInput,
        actor: Actor = Actor.User,
    ): Interview =
        update
            .execute(application.id, interview.id, input, interview.version, actor)
            .shouldBeInstanceOf<ApplicationResult.Success<Interview>>()
            .value

    private fun invalid(
        field: ApplicationField,
        problem: ApplicationProblem,
    ): ApplicationResult.Invalid = ApplicationResult.Invalid(listOf(ApplicationViolation(field, problem)))
}
