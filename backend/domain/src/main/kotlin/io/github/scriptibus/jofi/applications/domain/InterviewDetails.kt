// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.textProblem
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * When an interview starts (ADR-0048): the instant [startsAt], which orders and counts down, and the [zone] it was
 * planned in (an IANA region such as `Europe/Berlin`, or an offset), which shows it as the wall-clock time that
 * was agreed ([localStart]), whatever zone the user is in when they look. [startsAt] has microsecond precision, as
 * `timestamptz`, so a time read back equals the one stored, and lies in [EARLIEST] until [LATEST] (exclusive).
 */
data class InterviewTime(
    val startsAt: Instant,
    val zone: ZoneId,
) {
    init {
        require(startsAt == startsAt.truncatedTo(ChronoUnit.MICROS)) { "An interview time has microsecond precision" }
        require(!startsAt.isBefore(EARLIEST) && startsAt.isBefore(LATEST)) { "An interview time is out of range" }
        require(zone.id.length <= MAX_ZONE_ID_LENGTH) { "A time zone id is at most $MAX_ZONE_ID_LENGTH characters" }
    }

    /** The start on the clocks of [zone]. */
    val localStart: LocalDateTime get() = LocalDateTime.ofInstant(startsAt, zone)

    companion object {
        /** As the earliest discovery time: anything earlier is a typo. */
        val EARLIEST: Instant = ApplicationSource.EARLIEST_DISCOVERY

        /** Well within what `timestamptz` and every client can hold. */
        val LATEST: Instant = Instant.parse("2100-01-01T00:00:00Z")

        /** The longest IANA id has 32 characters; offsets and prefixed offsets are shorter. */
        const val MAX_ZONE_ID_LENGTH = 64

        /**
         * The time [local] on the clocks of [zone], truncated to microseconds, or `null` if it is out of range. A
         * local time a clock change skips (a gap) moves forward by the gap's length, one it repeats (an overlap)
         * takes the earlier offset, as `java.time` resolves them; the answer shows the resolved [localStart].
         */
        fun of(
            local: LocalDateTime,
            zone: ZoneId,
        ): InterviewTime? {
            val instant = local.atZone(zone).toInstant().truncatedTo(ChronoUnit.MICROS)
            return if (!instant.isBefore(EARLIEST) && instant.isBefore(LATEST)) InterviewTime(instant, zone) else null
        }

        /** The zone with id [raw] (trimmed), or `null` if Java's time zone database does not know it. */
        fun zoneOf(raw: String): ZoneId? {
            val id = raw.trim()
            if (id.isEmpty() || id.length > MAX_ZONE_ID_LENGTH) return null
            return try {
                ZoneId.of(id)
            } catch (_: DateTimeException) {
                null
            }
        }
    }
}

/**
 * What the user records about an interview or call (spec §6.1): its [type], [time], the contacts who took part
 * ([participants], at most [MAX_PARTICIPANTS]), [preparationNotes] and the user's [notes] afterwards (Markdown, the
 * user's words, which may describe other people), and the [outcome] once known. [toString] shows no notes and no
 * participants.
 */
data class InterviewDetails(
    val type: InterviewType,
    val time: InterviewTime,
    val participants: Set<ContactRef> = emptySet(),
    val preparationNotes: String? = null,
    val notes: String? = null,
    val outcome: InterviewOutcome? = null,
) {
    init {
        require(participants.size <= MAX_PARTICIPANTS) { "An interview has at most $MAX_PARTICIPANTS participants" }
        require(listOfNotNull(preparationNotes, notes).all { textProblem(it, MAX_NOTES_LENGTH) == null }) {
            "Interview notes break an invariant"
        }
    }

    override fun toString(): String =
        "InterviewDetails(type=$type, time=$time, participants=${participants.size}, outcome=$outcome)"

    companion object {
        const val MAX_PARTICIPANTS = 20

        /** As an application's portal notes. */
        const val MAX_NOTES_LENGTH = ApplicationDetails.MAX_NOTES_LENGTH
    }
}

/**
 * An interview or call as entered: [localStart] on the clocks of [timeZone] (an IANA id such as `Europe/Berlin`, or
 * an offset such as `+02:00`), as people agree on appointments. [validate] resolves the start (see
 * [InterviewTime.of]), normalizes the notes like every text (NFC, trimmed, blank is absent) and reports what is
 * wrong. Whether the participants exist is the store's check (`interview_participant_contact_fk`). [toString] shows
 * no notes.
 */
data class InterviewInput(
    val type: InterviewType,
    val localStart: LocalDateTime,
    val timeZone: String,
    val participants: Set<ContactRef> = emptySet(),
    val preparationNotes: String? = null,
    val notes: String? = null,
    val outcome: InterviewOutcome? = null,
) {
    fun validate(): ApplicationValidation<InterviewDetails> {
        val checks = InputChecks()
        val time = time(checks)
        if (participants.size > InterviewDetails.MAX_PARTICIPANTS) {
            checks.report(ApplicationField.PARTICIPANTS, ApplicationProblem.TOO_MANY)
        }
        val max = InterviewDetails.MAX_NOTES_LENGTH
        val preparationNotes = checks.text(ApplicationField.PREPARATION_NOTES, preparationNotes, max)
        val notes = checks.text(ApplicationField.INTERVIEW_NOTES, notes, max)
        if (time == null || checks.count > 0) return ApplicationValidation.Invalid(checks.violations)
        return ApplicationValidation.Valid(
            InterviewDetails(type, time, participants, preparationNotes, notes, outcome),
        )
    }

    private fun time(checks: InputChecks): InterviewTime? {
        val zone = InterviewTime.zoneOf(timeZone)
        if (zone == null) {
            checks.report(ApplicationField.TIME_ZONE, ApplicationProblem.INVALID_TIME_ZONE)
            return null
        }
        return InterviewTime.of(localStart, zone).also {
            if (it == null) checks.report(ApplicationField.INTERVIEW_START, ApplicationProblem.OUT_OF_RANGE)
        }
    }

    override fun toString(): String =
        "InterviewInput(type=$type, localStart=$localStart, timeZone=$timeZone, participants=${participants.size}, " +
            "outcome=$outcome)"
}
