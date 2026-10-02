// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.ApplicationValidation
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewEdit
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewInput
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.setup.adapter.persistence.ConfirmedProofs
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW_PARTICIPANT
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * `InterviewRepository` on a real PostgreSQL migrated from zero (ADR-0048): round trips of the instant and the zone
 * (also across clock changes), versioned updates that rewrite participants only when they change, foreign keys
 * mapped by name, the confirmed delete, and what the application and contact deletes cascade to.
 */
class InterviewRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: InterviewRepository

    /** The repository whose delete cascades to the interviews counts them (`interviewCount`). */
    private lateinit var applications: ApplicationRepository
    private lateinit var rows: ApplicationRows
    private lateinit var company: UUID
    private val application = ApplicationId(UUID.randomUUID())

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = InterviewRepository(dsl)
        applications = ApplicationRepository(dsl)
        rows = ApplicationRows(dsl)
        company = rows.company()
        rows.application(application.value, company)
    }

    private fun details(
        local: String = "2026-10-05T10:00",
        zone: String = "Europe/Berlin",
        participants: Set<ContactRef> = emptySet(),
    ): InterviewDetails =
        InterviewInput(InterviewType.PHONE_SCREEN, LocalDateTime.parse(local), zone, participants)
            .validate()
            .shouldBeInstanceOf<ApplicationValidation.Valid<InterviewDetails>>()
            .value

    private fun stored(
        details: InterviewDetails = details(),
        of: ApplicationId = application,
    ): Interview {
        val interview = Interview.log(InterviewId(UUID.randomUUID()), of, details, Actor.User, CREATED).interview
        repository.add(interview) shouldBe ApplicationStoreResult.Success(Unit)
        return interview
    }

    private fun Interview.edited(details: InterviewDetails): Interview =
        edit(details, Actor.User, EDITED).shouldBeInstanceOf<InterviewEdit.Changed>().interview

    private fun contact(): ContactRef = ContactRef(rows.contact(company))

    @Test
    fun `an interview with everything reads back equal, in the zone it was planned in`() {
        val full =
            details(participants = setOf(contact(), contact())).copy(
                preparationNotes = "# Prep\n\n- ask about \"on-call\"",
                notes = "Went well; İstanbul office ☕",
                outcome = InterviewOutcome.PASSED,
            )
        val interview = stored(full)

        repository.findById(application, interview.id) shouldBe ApplicationStoreResult.Success(interview)
        interview.details.time.startsAt shouldBe Instant.parse("2026-10-05T08:00:00Z")
    }

    @Test
    fun `times in a clock change gap and overlap round-trip as resolved`() {
        val gap = stored(details("2027-03-28T02:30"))
        val overlap = stored(details("2026-10-25T02:30"))
        val offset = stored(details("2026-10-25T02:30", "+05:30"))

        val read = listOf(gap, overlap, offset).map { read(it.id) }

        read.map {
            it.details.time.localStart
                .toString()
        } shouldContainExactly
            listOf("2027-03-28T03:30", "2026-10-25T02:30", "2026-10-25T02:30")
        read.map { it.details.time.startsAt } shouldContainExactly
            listOf(
                Instant.parse("2027-03-28T01:30:00Z"),
                Instant.parse("2026-10-25T00:30:00Z"),
                Instant.parse("2026-10-24T21:00:00Z"),
            )
        read.map { it.details.time.zone.id } shouldContainExactly listOf("Europe/Berlin", "Europe/Berlin", "+05:30")
    }

    @Test
    fun `the list is in start order, with participants, and the count matches`() {
        val erika = contact()
        val later = stored(details("2026-10-09T09:00", participants = setOf(erika)))
        val sooner = stored(details("2026-10-05T10:00", "America/New_York"))
        val otherApplication = ApplicationId(UUID.randomUUID()).also { rows.application(it.value, company) }
        stored(of = otherApplication)

        page(application, 0, 10).items shouldBe listOf(sooner, later)
        applications.interviewCount(application) shouldBe ApplicationStoreResult.Success(2)
        page(ApplicationId(UUID.randomUUID()), 0, 10).items shouldBe emptyList()
    }

    @Test
    fun `pages in either direction reach every interview exactly once, with the total`() {
        val all = (1..7).map { stored(details("2026-10-${10 + it}T10:00")) }
        stored(of = ApplicationId(UUID.randomUUID()).also { rows.application(it.value, company) })

        val oldest = (0..2).map { page(application, it, 3) }
        val newest = (0..2).map { page(application, it, 3, SortDirection.DESCENDING) }

        oldest.flatMap { it.items } shouldBe all
        newest.flatMap { it.items } shouldBe all.reversed()
        oldest.map { it.info.total } shouldBe listOf(7, 7, 7)
        oldest.map { it.info.hasMore } shouldBe listOf(true, true, false)
        page(application, 3, 3).items shouldBe emptyList()
    }

    @Test
    fun `interviews that start at the same instant keep one order over every page, by id`() {
        val same = (1..5).map { stored(details("2026-10-05T10:00")) }

        val ascending = (0..2).flatMap { page(application, it, 2).items }.map { it.id.value.toString() }
        val descending =
            (0..2).flatMap { page(application, it, 2, SortDirection.DESCENDING).items }.map { it.id.value.toString() }

        // PostgreSQL orders uuids bytewise, which is the order of their lower-case text.
        ascending shouldBe same.map { it.id.value.toString() }.sorted()
        descending shouldBe same.map { it.id.value.toString() }.sortedDescending()
    }

    @Test
    fun `an interview added between two page reads shows in the changed total`() {
        (1..4).forEach { stored(details("2026-10-${10 + it}T10:00")) }
        val before = page(application, 0, 2, SortDirection.DESCENDING)

        stored(details("2026-11-20T10:00"))
        val after = page(application, 1, 2, SortDirection.DESCENDING)

        before.info.total shouldBe 4
        after.info.total shouldBe 5
        // The new newest one pushed the last entry of page 0 onto page 1: a repeat, never a gap.
        after.items.first().id shouldBe before.items.last().id
    }

    private fun page(
        of: ApplicationId,
        page: Int,
        size: Int,
        direction: SortDirection = SortDirection.ASCENDING,
    ): Paged<Interview> =
        repository
            .pageByApplication(of, PageRequest(page, size), direction)
            .shouldBeInstanceOf<ApplicationStoreResult.Success<Paged<Interview>>>()
            .value

    @Test
    fun `an update stores on top of its version and rewrites participants only when they change`() {
        val erika = contact()
        val interview = stored(details(participants = setOf(erika)))
        val before = participantRows()

        val withNotes = interview.edited(details(participants = setOf(erika)).copy(notes = "Went well"))
        repository.update(withNotes) shouldBe ApplicationStoreResult.Success(Unit)
        participantRows() shouldBe before

        val max = contact()
        val moved = withNotes.edited(details("2026-10-06T14:00", participants = setOf(max)).copy(notes = "Went well"))
        repository.update(moved) shouldBe ApplicationStoreResult.Success(Unit)
        participantRows() shouldNotBe before
        repository.findById(application, interview.id) shouldBe ApplicationStoreResult.Success(moved)

        repository.update(withNotes) shouldBe ApplicationStoreResult.VersionConflict
        repository.update(moved.copy(id = InterviewId(UUID.randomUUID()))) shouldBe ApplicationStoreResult.NotFound
        repository.update(moved.copy(application = ApplicationId(UUID.randomUUID()), version = 3)) shouldBe
            ApplicationStoreResult.NotFound
    }

    @Test
    fun `unknown applications and participants are refused by the names of their foreign keys`() {
        val unknownApplication =
            Interview
                .log(
                    InterviewId(UUID.randomUUID()),
                    ApplicationId(UUID.randomUUID()),
                    details(),
                    Actor.User,
                    CREATED,
                ).interview
        repository.add(unknownApplication) shouldBe ApplicationStoreResult.NotFound

        val stranger = setOf(ContactRef(UUID.randomUUID()))
        val withStranger =
            Interview.log(
                InterviewId(UUID.randomUUID()),
                application,
                details(participants = stranger),
                Actor.User,
                CREATED,
            )
        repository.add(withStranger.interview) shouldBe ApplicationStoreResult.ContactNotFound
        val interview = stored()
        repository.update(interview.edited(details(participants = stranger))) shouldBe
            ApplicationStoreResult.ContactNotFound
    }

    @Test
    fun `a delete needs the proof for exactly this interview and leaves the contacts`() {
        val erika = contact()
        val interview = stored(details(participants = setOf(erika)))
        val other = stored()

        repository.delete(application, interview.id, proof(other.id)) shouldBe ApplicationStoreResult.NotConfirmed
        repository.delete(ApplicationId(UUID.randomUUID()), interview.id, proof(interview.id)) shouldBe
            ApplicationStoreResult.NotFound
        repository.delete(application, interview.id, proof(interview.id)) shouldBe ApplicationStoreResult.Success(Unit)

        repository.findById(application, interview.id) shouldBe ApplicationStoreResult.NotFound
        dsl.fetchCount(INTERVIEW_PARTICIPANT) shouldBe 0
        dsl.fetchCount(CONTACT) shouldBe 1
        applications.interviewCount(application) shouldBe ApplicationStoreResult.Success(1)
    }

    @Test
    fun `interviews go with their application, a deleted contact only leaves the participants`() {
        val erika = contact()
        val max = contact()
        val interview = stored(details(participants = setOf(erika, max)))

        dsl.deleteFrom(CONTACT).where(CONTACT.ID.eq(erika.value)).execute()
        read(interview.id).details.participants shouldBe setOf(max)

        dsl.deleteFrom(APPLICATION).where(APPLICATION.ID.eq(application.value)).execute()
        page(application, 0, 10).items shouldBe emptyList()
        dsl.fetchCount(INTERVIEW) shouldBe 0
        dsl.fetchCount(INTERVIEW_PARTICIPANT) shouldBe 0
    }

    @Test
    fun `upcoming interviews start now or later, are not cancelled and carry the application title`() {
        val past = stored(details("2026-09-01T10:00"))
        val soon = stored(details("2026-10-05T10:00"))
        val later = stored(details("2026-11-05T10:00"))
        repository.update(later.edited(later.details.copy(outcome = InterviewOutcome.CANCELLED)))
        val decided =
            stored(
                details("2026-12-01T10:00"),
            ).let { it.edited(it.details.copy(outcome = InterviewOutcome.PASSED)) }
        repository.update(decided)

        repository.upcoming(Instant.parse("2026-10-01T00:00:00Z"), 10) shouldBe
            ApplicationStoreResult.Success(
                listOf(UpcomingInterview(soon, "Backend Engineer"), UpcomingInterview(decided, "Backend Engineer")),
            )
        repository.upcoming(past.details.time.startsAt, 1) shouldBe
            ApplicationStoreResult.Success(listOf(UpcomingInterview(past, "Backend Engineer")))
    }

    @Test
    fun `interviews of closed applications are not upcoming, those of every pipeline status are`() {
        val byStatus =
            ApplicationStatus.entries.associateWith { status ->
                val of = ApplicationId(UUID.randomUUID())
                rows.application(of.value, company) {
                    this.status = status.name
                    if (status.takesDeclineReason) declineCategory = DeclineCategory.SALARY.name
                }
                stored(details("2026-10-05T10:00"), of)
            }

        val upcoming =
            repository
                .upcoming(Instant.parse("2026-10-01T00:00:00Z"), 100)
                .shouldBeInstanceOf<ApplicationStoreResult.Success<List<UpcomingInterview>>>()
                .value
                .map { it.interview }

        upcoming shouldContainExactlyInAnyOrder byStatus.filterKeys { !it.isTerminal }.values
    }

    private fun read(id: InterviewId): Interview =
        repository.findById(application, id).shouldBeInstanceOf<ApplicationStoreResult.Success<Interview>>().value

    private fun participantRows(): List<String> =
        dsl.fetchValues("select ctid::text from interview_participant order by ctid").map(Any?::toString)

    private fun proof(id: InterviewId) = ConfirmedProofs.of(Interview.DELETE_OPERATION, id.value.toString())

    private companion object {
        val CREATED: Instant = Instant.parse("2026-09-30T08:00:00.123456Z")
        val EDITED: Instant = Instant.parse("2026-09-30T09:00:00Z")
    }
}
