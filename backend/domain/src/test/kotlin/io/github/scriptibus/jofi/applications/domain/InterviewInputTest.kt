// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class InterviewInputTest {
    private val local = LocalDateTime.parse("2026-10-05T10:00")
    private val input = InterviewInput(InterviewType.HR, local, "Europe/Berlin")

    private fun valid(input: InterviewInput): InterviewDetails =
        input.validate().shouldBeInstanceOf<ApplicationValidation.Valid<InterviewDetails>>().value

    private fun violations(input: InterviewInput): List<ApplicationViolation> =
        input.validate().shouldBeInstanceOf<ApplicationValidation.Invalid>().violations

    @Test
    fun `the start is the agreed wall-clock time in the given zone`() {
        val details = valid(input.copy(timeZone = " Europe/Berlin "))

        details.time shouldBe InterviewTime(Instant.parse("2026-10-05T08:00:00Z"), ZoneId.of("Europe/Berlin"))
        valid(input.copy(timeZone = "+05:30")).time.startsAt shouldBe Instant.parse("2026-10-05T04:30:00Z")
        valid(input.copy(timeZone = "UTC")).time.localStart shouldBe local
    }

    @Test
    fun `the start is truncated to microseconds, the precision it is stored with`() {
        valid(input.copy(localStart = local.plusNanos(123_456_789))).time.startsAt shouldBe
            Instant.parse("2026-10-05T08:00:00.123456Z")
    }

    @Test
    fun `clock changes resolve as java time does`() {
        // 2026-03-29 02:30 does not exist in Berlin (a gap), 2026-10-25 02:30 happens twice (an overlap).
        valid(input.copy(localStart = LocalDateTime.parse("2026-03-29T02:30"))).time.localStart shouldBe
            LocalDateTime.parse("2026-03-29T03:30")
        valid(input.copy(localStart = LocalDateTime.parse("2026-10-25T02:30"))).time.startsAt shouldBe
            Instant.parse("2026-10-25T00:30:00Z")
    }

    @Test
    fun `notes are normalized and blank notes are absent`() {
        val details = valid(input.copy(preparationNotes = " Café ", notes = "   ", outcome = InterviewOutcome.PASSED))

        details.preparationNotes shouldBe "Café"
        details.notes shouldBe null
        details.outcome shouldBe InterviewOutcome.PASSED
    }

    @Test
    fun `an unknown time zone is reported, and the start is not checked without one`() {
        listOf("", "Europe/Nowhere", "europe/berlin", "CEST", "x".repeat(InterviewTime.MAX_ZONE_ID_LENGTH + 1))
            .forEach {
                violations(input.copy(timeZone = it, localStart = LocalDateTime.parse("1999-01-01T00:00"))) shouldBe
                    listOf(ApplicationViolation(ApplicationField.TIME_ZONE, ApplicationProblem.INVALID_TIME_ZONE))
            }
    }

    @Test
    fun `a start outside the range is reported`() {
        listOf("1999-12-31T23:59:59", "2100-01-01T00:00", "2000-01-01T00:30").forEach {
            val zone = if (it.startsWith("2000")) "Europe/Berlin" else "UTC"
            violations(input.copy(localStart = LocalDateTime.parse(it), timeZone = zone)) shouldBe
                listOf(ApplicationViolation(ApplicationField.INTERVIEW_START, ApplicationProblem.OUT_OF_RANGE))
        }
        valid(input.copy(localStart = LocalDateTime.parse("2000-01-01T00:00"), timeZone = "UTC")).time.startsAt shouldBe
            InterviewTime.EARLIEST
    }

    @Test
    fun `every other problem is reported at once`() {
        val tooMany = List(InterviewDetails.MAX_PARTICIPANTS + 1) { ContactRef(UUID.randomUUID()) }.toSet()
        val tooLong = "x".repeat(InterviewDetails.MAX_NOTES_LENGTH + 1)

        violations(input.copy(participants = tooMany, preparationNotes = tooLong, notes = "Went\u0000well")) shouldBe
            listOf(
                ApplicationViolation(ApplicationField.PARTICIPANTS, ApplicationProblem.TOO_MANY),
                ApplicationViolation(ApplicationField.PREPARATION_NOTES, ApplicationProblem.TOO_LONG),
                ApplicationViolation(ApplicationField.INTERVIEW_NOTES, ApplicationProblem.INVALID_CHARACTER),
            )
    }

    @Test
    fun `exactly the limits are valid`() {
        val participants = List(InterviewDetails.MAX_PARTICIPANTS) { ContactRef(UUID.randomUUID()) }.toSet()
        val longest = "x".repeat(InterviewDetails.MAX_NOTES_LENGTH)

        valid(input.copy(participants = participants, notes = longest)).participants shouldBe participants
    }

    @Test
    fun `the input prints no notes`() {
        input.copy(preparationNotes = "Secret", notes = "Secret").toString() shouldNotContain "Secret"
    }
}
