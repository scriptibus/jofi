// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.ChangelogRepository
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** The "no answer since" query of the Ghosted suggestion (#85) and the follow-up (#95) against the real schema. */
class ApplicationActivityRepositoryTest {
    private val dsl = PostgresTestDatabase.migratedFromZero()
    private val rows = ApplicationRows(dsl)
    private val repository = ApplicationActivityRepository(dsl)
    private val changelog = ChangelogRepository(dsl)
    private val company = rows.company()

    private val applied = NOW.minusWeeks(20)
    private val cutoff: Instant = NOW.minusWeeks(14).toInstant()

    @Test
    fun `applied and interviewing applications silent since the cutoff are candidates, longest silent first`() {
        val interviewing = application("INTERVIEWING", NOW.minusWeeks(16))
        val appliedLongAgo = application("APPLIED", applied)
        val exactlyAtCutoff = application("APPLIED", NOW.minusWeeks(14))
        application("APPLIED", NOW.minusWeeks(13))
        application("OFFER", applied)
        application("GHOSTED", applied)
        application("DISCOVERED", applied)

        candidates() shouldBe
            listOf(
                candidate(appliedLongAgo, applied),
                candidate(interviewing, NOW.minusWeeks(16)),
                candidate(exactlyAtCutoff, NOW.minusWeeks(14)),
            )
    }

    @Test
    fun `the latest status change counts, also a move between applied and interviewing`() {
        val id = application("APPLIED", applied)
        statusChange(id, "INTERVIEWING", NOW.minusWeeks(10))

        candidates() shouldBe emptyList()
    }

    @Test
    fun `an interview edited, taking place or still to come after the cutoff keeps the application active`() {
        val edited = application("INTERVIEWING", applied)
        interview(edited, startsAt = NOW.minusWeeks(19), updatedAt = NOW.minusWeeks(2))
        val recent = application("INTERVIEWING", applied)
        interview(recent, startsAt = NOW.minusWeeks(3), updatedAt = NOW.minusWeeks(19))
        val upcoming = application("INTERVIEWING", applied)
        interview(upcoming, startsAt = NOW.plusWeeks(1), updatedAt = NOW.minusWeeks(19))
        val longAgo = application("INTERVIEWING", applied)
        interview(longAgo, startsAt = NOW.minusWeeks(18), updatedAt = NOW.minusWeeks(19))

        candidates() shouldBe listOf(candidate(longAgo, NOW.minusWeeks(18)))
    }

    @Test
    fun `a change of the contact links counts, other changelog entries and other applications do not`() {
        val linked = application("APPLIED", applied)
        record(linked, "contacts", NOW.minusWeeks(1))
        val edited = application("APPLIED", applied)
        record(edited, "notes", NOW.minusWeeks(1))
        record(UUID.randomUUID(), "contacts", NOW.minusWeeks(1))

        candidates() shouldBe listOf(candidate(edited, applied))
    }

    @Test
    fun `a database it cannot reach is a storage failure, not an exception`() {
        ApplicationActivityRepository(DSL.using(SQLDialect.POSTGRES)).silentSince(cutoff, WAITING) shouldBe
            ApplicationStoreResult.StorageFailure("silentSince")
    }

    @Test
    fun `only the statuses asked for are candidates`() {
        val waiting = application("APPLIED", applied)
        application("INTERVIEWING", applied)

        candidates(setOf(ApplicationStatus.APPLIED)) shouldBe listOf(candidate(waiting, applied))
    }

    private fun candidates(statuses: Set<ApplicationStatus> = WAITING): List<FindGhostedCandidatesPort.Candidate> =
        (repository.silentSince(cutoff, statuses) as ApplicationStoreResult.Success).value

    private fun candidate(
        id: UUID,
        since: OffsetDateTime,
    ) = FindGhostedCandidatesPort.Candidate(id, "Backend Engineer", since.toInstant())

    /** An application created well before [enteredAt], when it moved to [status]. */
    private fun application(
        status: String,
        enteredAt: OffsetDateTime,
    ): UUID {
        val id = UUID.randomUUID()
        rows.application(id, company) {
            this.status = status
            createdAt = enteredAt.minus(Duration.ofDays(30))
        }
        statusChange(id, "DISCOVERED", enteredAt.minus(Duration.ofDays(30)))
        statusChange(id, status, enteredAt)
        return id
    }

    private fun statusChange(
        application: UUID,
        status: String,
        at: OffsetDateTime,
    ) {
        dsl
            .newRecord(APPLICATION_STATUS_CHANGE)
            .apply {
                applicationId = application
                toStatus = status
                actorKind = "USER"
                changedAt = at
            }.insert()
    }

    private fun interview(
        application: UUID,
        startsAt: OffsetDateTime,
        updatedAt: OffsetDateTime,
    ) {
        dsl
            .newRecord(INTERVIEW)
            .apply {
                id = UUID.randomUUID()
                applicationId = application
                kind = "HR"
                this.startsAt = startsAt
                timeZone = "Europe/Berlin"
                createdAt = updatedAt
                this.updatedAt = updatedAt
            }.insert()
    }

    private fun record(
        application: UUID,
        field: String,
        at: OffsetDateTime,
    ) {
        val change = ChangeSummary("Changed application", listOf(FieldChange(field, null, "x")))
        val entity = EntityRef("application", application.toString())
        changelog.append(ChangelogEntry(entity, Actor.User, at.toInstant(), change))
    }

    private companion object {
        val WAITING = setOf(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEWING)
    }
}
