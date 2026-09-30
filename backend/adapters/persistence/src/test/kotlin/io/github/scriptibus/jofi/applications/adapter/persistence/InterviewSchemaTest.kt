// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationValidation
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewInput
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW_PARTICIPANT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.InterviewRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `interview` and `interview_participant` on a real PostgreSQL (ADR-0041, ADR-0048): their constraints mirror the
 * domain without being stricter, each has a name, every time zone Java knows is storable, interviews go with their
 * application, and a deleted contact (or its company) only leaves the interviews it took part in.
 */
class InterviewSchemaTest {
    private lateinit var dsl: DSLContext
    private lateinit var rows: ApplicationRows
    private lateinit var company: UUID
    private val application = UUID.randomUUID()

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        rows = ApplicationRows(dsl)
        company = rows.company()
        rows.application(application, company)
    }

    @Test
    fun `every constraint has a name of its own`() {
        rows.constraintsOf("interview") shouldBe INTERVIEW_CONSTRAINTS
        rows.constraintsOf("interview_participant") shouldBe
            listOf("interview_participant_contact_fk", "interview_participant_interview_fk", "interview_participant_pk")
    }

    @Test
    fun `stores every type and outcome the domain has`() {
        InterviewType.entries.forEach { type -> store(details(type = type)) }
        InterviewOutcome.entries.forEach { outcome -> store(details(outcome = outcome)) }

        dsl.fetchCount(INTERVIEW) shouldBe InterviewType.entries.size + InterviewOutcome.entries.size
    }

    @Test
    fun `stores every time zone Java knows, and the offsets`() {
        val zones = ZoneId.getAvailableZoneIds() + listOf("Z", "+14:00", "-12:00", "UTC+05:30", "GMT-03:00")

        zones.forEach { store(details(timeZone = it)) }

        dsl.fetchValues(INTERVIEW.TIME_ZONE).toSet() shouldBe zones.map { ZoneId.of(it).id }.toSet()
    }

    @Test
    fun `stores whatever the domain accepts, at exactly its limits`() {
        // Exactly the limit in UTF-16 units, which the domain counts (the rocket is two).
        val longest = "ü".repeat(InterviewDetails.MAX_NOTES_LENGTH - 3) + " 🚀"
        val participants = List(InterviewDetails.MAX_PARTICIPANTS) { ContactRef(rows.contact(company)) }.toSet()
        val earliest = LocalDateTime.ofInstant(InterviewTime.EARLIEST, ZoneOffset.UTC)
        val latest = LocalDateTime.ofInstant(InterviewTime.LATEST.minus(1, ChronoUnit.MICROS), ZoneOffset.UTC)
        val inputs =
            listOf(
                input(earliest, "UTC").copy(participants = participants, preparationNotes = longest, notes = longest),
                input(latest, "UTC").copy(notes = "# İstanbul\n\n Straße, \"Kotlin\"; 𝔘𝔫𝔦𝔠𝔬𝔡𝔢 ☕"),
                input(LocalDateTime.parse("2026-10-05T10:15:30.123456789"), "Europe/Berlin"),
            )

        inputs.forEach { input ->
            store(input.validate().shouldBeInstanceOf<ApplicationValidation.Valid<InterviewDetails>>().value)
        }

        dsl.fetchValues(INTERVIEW.STARTS_AT).map { it.toInstant() }.toSet() shouldBe
            setOf(InterviewTime.EARLIEST, InterviewTime.LATEST.minus(1, ChronoUnit.MICROS), BERLIN_START)
        dsl.fetchCount(INTERVIEW_PARTICIPANT) shouldBe InterviewDetails.MAX_PARTICIPANTS
    }

    @Test
    fun `rejects interviews the domain rejects`() {
        rejects("interview_kind_valid") { insert { kind = "technical" } }
        rejects("interview_time_zone_valid") { insert { timeZone = "" } }
        rejects("interview_time_zone_valid") { insert { timeZone = "Europe/ Berlin" } }
        rejects("interview_time_zone_valid") { insert { timeZone = "x".repeat(InterviewTime.MAX_ZONE_ID_LENGTH + 1) } }
        rejects("interview_preparation_notes_valid") { insert { preparationNotes = " \n" } }
        rejects("interview_preparation_notes_valid") { insert { preparationNotes = "Prep\n" } }
        rejects("interview_notes_valid") { insert { notes = " Notes" } }
        rejects("interview_notes_valid") { insert { notes = "x".repeat(InterviewDetails.MAX_NOTES_LENGTH + 1) } }
        rejects("interview_outcome_valid") { insert { outcome = "passed" } }
        rejects("interview_version_valid") { insert { version = -1L } }
        rejects("interview_updated_after_created") { insert { updatedAt = NOW.minusSeconds(1) } }
        rejects("interview_application_fk") { insert { applicationId = UUID.randomUUID() } }
        dsl.fetchCount(INTERVIEW) shouldBe 0
    }

    @Test
    fun `a contact takes part once, and only an existing one in an existing interview`() {
        val interview = insert()
        val contact = rows.contact(company)
        participate(interview, contact)

        rejects("interview_participant_pk") { participate(interview, contact) }
        rejects("interview_participant_contact_fk") { participate(interview, UUID.randomUUID()) }
        rejects("interview_participant_interview_fk") { participate(UUID.randomUUID(), contact) }
    }

    @Test
    fun `PostgreSQL cannot store U+0000, which is why the domain rejects it`() {
        shouldThrow<DataAccessException> { insert { notes = "Went\u0000well" } }
        input(LocalDateTime.parse("2026-10-05T10:00"), "UTC")
            .copy(notes = "Went\u0000well")
            .validate()
            .shouldBeInstanceOf<ApplicationValidation.Invalid>()
    }

    @Test
    fun `deleting a contact or its company removes it from the interviews, which stay`() {
        val interview = insert()
        val contact = rows.contact(null)
        val otherCompany = rows.company()
        participate(interview, contact)
        participate(interview, rows.contact(otherCompany))

        dsl.deleteFrom(COMPANY).where(COMPANY.ID.eq(otherCompany)).execute()
        dsl.fetchValues(INTERVIEW_PARTICIPANT.CONTACT_ID) shouldBe listOf(contact)

        dsl.deleteFrom(CONTACT).where(CONTACT.ID.eq(contact)).execute()
        dsl.fetchCount(INTERVIEW_PARTICIPANT) shouldBe 0
        dsl.fetchCount(INTERVIEW) shouldBe 1
    }

    @Test
    fun `interviews and their participant rows go with the application, the contacts stay`() {
        participate(insert(), rows.contact(company))

        dsl.deleteFrom(APPLICATION).where(APPLICATION.ID.eq(application)).execute()

        dsl.fetchCount(INTERVIEW) shouldBe 0
        dsl.fetchCount(INTERVIEW_PARTICIPANT) shouldBe 0
        dsl.fetchCount(CONTACT) shouldBe 1
    }

    private fun input(
        local: LocalDateTime,
        timeZone: String,
    ): InterviewInput = InterviewInput(InterviewType.TECHNICAL, local, timeZone)

    private fun details(
        type: InterviewType = InterviewType.HR,
        outcome: InterviewOutcome? = null,
        timeZone: String = "Europe/Berlin",
    ): InterviewDetails = InterviewDetails(type, InterviewTime(NOW.toInstant(), ZoneId.of(timeZone)), outcome = outcome)

    // What the repository (#91) will write for a domain interview.
    private fun store(details: InterviewDetails) {
        val at = NOW.toInstant()
        val interview = Interview(InterviewId(UUID.randomUUID()), ApplicationId(application), details, 0, at, at)
        insert {
            id = interview.id.value
            kind = details.type.name
            startsAt = details.time.startsAt.atOffset(ZoneOffset.UTC)
            timeZone = details.time.zone.id
            preparationNotes = details.preparationNotes
            notes = details.notes
            outcome = details.outcome?.name
        }
        details.participants.forEach { participate(interview.id.value, it.value) }
    }

    private fun insert(customize: InterviewRecord.() -> Unit = {}): UUID {
        val record =
            dsl.newRecord(INTERVIEW).apply {
                id = UUID.randomUUID()
                applicationId = application
                kind = "HR"
                startsAt = NOW
                timeZone = "Europe/Berlin"
                createdAt = NOW
                updatedAt = NOW
                customize()
            }
        record.insert()
        return record.id
    }

    private fun participate(
        interview: UUID,
        contact: UUID,
    ) {
        dsl
            .insertInto(INTERVIEW_PARTICIPANT, INTERVIEW_PARTICIPANT.INTERVIEW_ID, INTERVIEW_PARTICIPANT.CONTACT_ID)
            .values(interview, contact)
            .execute()
    }

    private companion object {
        val BERLIN_START: Instant = Instant.parse("2026-10-05T08:15:30.123456Z")
        val INTERVIEW_CONSTRAINTS =
            listOf(
                "interview_application_fk",
                "interview_kind_valid",
                "interview_notes_valid",
                "interview_outcome_valid",
                "interview_pk",
                "interview_preparation_notes_valid",
                "interview_time_zone_valid",
                "interview_updated_after_created",
                "interview_version_valid",
            )
    }
}
