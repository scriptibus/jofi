// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.DomainEvent
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class InterviewTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val id = InterviewId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"))
    private val application = ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val berlin = ZoneId.of("Europe/Berlin")
    private val utc = ZoneId.of("Z")
    private val time = InterviewTime(Instant.parse("2026-10-05T08:00:00Z"), berlin)
    private val details =
        InterviewDetails(
            InterviewType.TECHNICAL,
            time,
            setOf(ContactRef(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))),
            "Secret prep",
            "Secret notes",
        )
    private val logged = Interview.log(id, application, details, Actor.Ai, at)

    @Test
    fun `changelog entries refer to it as an interview`() {
        id.toEntityRef() shouldBe EntityRef("interview", id.value.toString())
    }

    @Test
    fun `logging creates the first version and announces it`() {
        logged.interview shouldBe Interview(id, application, details, Interview.INITIAL_VERSION, at, at)
        logged.event shouldBe InterviewScheduled(id, application, InterviewType.TECHNICAL, time, Actor.Ai, at)
        logged.event.shouldBeInstanceOf<DomainEvent>()
    }

    @Test
    fun `an unchanged edit is no new version and announces nothing`() {
        logged.interview.edit(details, Actor.User, at.plusSeconds(60)) shouldBe InterviewEdit.Unchanged
    }

    @Test
    fun `an edit that keeps the start is a new version without a reschedule`() {
        val later = at.plusSeconds(60)
        val withOutcome = details.copy(outcome = InterviewOutcome.PASSED, time = InterviewTime(time.startsAt, utc))

        val edit = logged.interview.edit(withOutcome, Actor.User, later).shouldBeInstanceOf<InterviewEdit.Changed>()

        edit.interview shouldBe logged.interview.copy(details = withOutcome, version = 1, updatedAt = later)
        edit.rescheduled shouldBe null
    }

    @Test
    fun `moving the start is announced with both times`() {
        val later = at.plusSeconds(60)
        val moved = InterviewTime(time.startsAt.plusSeconds(3600), berlin)

        val edit =
            logged.interview
                .edit(details.copy(time = moved), Actor.User, later)
                .shouldBeInstanceOf<InterviewEdit.Changed>()

        edit.rescheduled shouldBe InterviewRescheduled(id, application, time, moved, Actor.User, later)
        edit.interview.version shouldBe 1
    }

    @Test
    fun `invariants hold`() {
        shouldThrow<IllegalArgumentException> { logged.interview.copy(version = -1) }
        shouldThrow<IllegalArgumentException> { logged.interview.copy(updatedAt = at.minusSeconds(1)) }
        val tooMany = List(InterviewDetails.MAX_PARTICIPANTS + 1) { ContactRef(UUID.randomUUID()) }.toSet()
        shouldThrow<IllegalArgumentException> { details.copy(participants = tooMany) }
        shouldThrow<IllegalArgumentException> { details.copy(notes = " untrimmed") }
        shouldThrow<IllegalArgumentException> { details.copy(preparationNotes = "") }
        shouldThrow<IllegalArgumentException> {
            details.copy(notes = "x".repeat(InterviewDetails.MAX_NOTES_LENGTH + 1))
        }
    }

    @Test
    fun `a time has microsecond precision and a range`() {
        shouldThrow<IllegalArgumentException> { InterviewTime(time.startsAt.plusNanos(1), berlin) }
        shouldThrow<IllegalArgumentException> { InterviewTime(InterviewTime.EARLIEST.minusNanos(1_000), berlin) }
        shouldThrow<IllegalArgumentException> { InterviewTime(InterviewTime.LATEST, berlin) }
        InterviewTime(InterviewTime.EARLIEST, berlin).startsAt shouldBe InterviewTime.EARLIEST
    }

    @Test
    fun `a time shows the agreed wall-clock time in its zone`() {
        time.localStart shouldBe LocalDateTime.parse("2026-10-05T10:00")
        InterviewTime(time.startsAt, ZoneId.of("America/New_York")).localStart shouldBe
            LocalDateTime.parse("2026-10-05T04:00")
    }

    @Test
    fun `nothing personal is printed`() {
        val upcoming = UpcomingInterview(logged.interview, "Secret title")

        listOf(logged.interview, details, upcoming).forEach {
            it.toString() shouldNotContain "Secret"
            it.toString() shouldNotContain "0000000000c1"
        }
    }
}
