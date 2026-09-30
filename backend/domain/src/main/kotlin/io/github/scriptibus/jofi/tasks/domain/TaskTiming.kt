// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * When a task is due (spec §10.2, ADR-0049): at an [Exact] moment, or some time within a [Bucket] of days. Both are
 * absolute, so a task put in "this week" last week is overdue now, not due again.
 */
sealed interface TaskTiming {
    /**
     * Due at the instant [dueAt], planned in [zone] (an IANA id or an offset), as interviews are (ADR-0048): the
     * instant orders and counts down, the zone shows the agreed wall-clock time ([localDue]). Microsecond precision,
     * within [EARLIEST] until [LATEST] (exclusive).
     */
    data class Exact(
        val dueAt: Instant,
        val zone: ZoneId,
    ) : TaskTiming {
        init {
            require(dueAt == dueAt.truncatedTo(ChronoUnit.MICROS)) { "A due time has microsecond precision" }
            require(!dueAt.isBefore(EARLIEST) && dueAt.isBefore(LATEST)) { "A due time is out of range" }
            require(zone.id.length <= MAX_ZONE_ID_LENGTH) { "A time zone id is at most $MAX_ZONE_ID_LENGTH characters" }
        }

        /** The due time on the clocks of [zone]. */
        val localDue: LocalDateTime get() = LocalDateTime.ofInstant(dueAt, zone)

        companion object {
            /**
             * [local] on the clocks of [zone], truncated to microseconds, or `null` if it is out of range. Gaps and
             * overlaps of clock changes resolve as `java.time` does (ADR-0048).
             */
            fun of(
                local: LocalDateTime,
                zone: ZoneId,
            ): Exact? {
                val instant = local.atZone(zone).toInstant().truncatedTo(ChronoUnit.MICROS)
                return if (!instant.isBefore(EARLIEST) && instant.isBefore(LATEST)) Exact(instant, zone) else null
            }
        }
    }

    /**
     * Due some time in the [span] starting [startsOn]: that day, the week from that Monday, the month from that first
     * day, or someday (no start). The days are dates without a zone: whoever looks sees them on their own calendar.
     */
    data class Bucket(
        val span: BucketSpan,
        val startsOn: LocalDate?,
    ) : TaskTiming {
        init {
            require((startsOn == null) == (span == BucketSpan.SOMEDAY)) { "Only someday has no first day" }
            if (startsOn != null) {
                require(
                    !startsOn.isBefore(EARLIEST_DAY) && startsOn.isBefore(LATEST_DAY),
                ) { "A bucket is out of range" }
                require(span != BucketSpan.WEEK || startsOn.dayOfWeek == DayOfWeek.MONDAY) { "A week starts on Monday" }
                require(span != BucketSpan.MONTH || startsOn.dayOfMonth == 1) { "A month starts on its first day" }
            }
        }

        /** The first day after the bucket (overdue from then on), or `null` for someday. */
        val endsBefore: LocalDate?
            get() =
                when (span) {
                    BucketSpan.DAY -> startsOn?.plusDays(1)
                    BucketSpan.WEEK -> startsOn?.plusWeeks(1)
                    BucketSpan.MONTH -> startsOn?.plusMonths(1)
                    BucketSpan.SOMEDAY -> null
                }

        companion object {
            val SOMEDAY: Bucket = Bucket(BucketSpan.SOMEDAY, null)
        }
    }

    companion object {
        /** Anything earlier is a typo (as interviews and discovery times). */
        val EARLIEST: Instant = Instant.parse("2000-01-01T00:00:00Z")

        /** Well within what `timestamptz` and every client can hold. */
        val LATEST: Instant = Instant.parse("2100-01-01T00:00:00Z")

        /** The first day a bucket may start on. */
        val EARLIEST_DAY: LocalDate = LocalDate.of(2000, 1, 1)

        /** The first day a bucket may no longer start on. */
        val LATEST_DAY: LocalDate = LocalDate.of(2100, 1, 1)

        /** As `InterviewTime.MAX_ZONE_ID_LENGTH`. */
        const val MAX_ZONE_ID_LENGTH = 64

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

/** How long a [TaskTiming.Bucket] lasts. Never rename a constant: the database stores the names. */
enum class BucketSpan { DAY, WEEK, MONTH, SOMEDAY }

/** The rough times a user picks (spec §10.2), relative to the day they pick one; weeks start on Monday. */
enum class TimeBucket {
    TODAY,
    THIS_WEEK,
    NEXT_WEEK,
    THIS_MONTH,
    SOMEDAY,
    ;

    /** The absolute bucket this means on [today], or `null` if it would start out of range. */
    fun on(today: LocalDate): TaskTiming.Bucket? {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val (span, start) =
            when (this) {
                TODAY -> BucketSpan.DAY to today
                THIS_WEEK -> BucketSpan.WEEK to monday
                NEXT_WEEK -> BucketSpan.WEEK to monday.plusWeeks(1)
                THIS_MONTH -> BucketSpan.MONTH to today.withDayOfMonth(1)
                SOMEDAY -> return TaskTiming.Bucket.SOMEDAY
            }
        val inRange = !start.isBefore(TaskTiming.EARLIEST_DAY) && start.isBefore(TaskTiming.LATEST_DAY)
        return if (inRange) TaskTiming.Bucket(span, start) else null
    }
}

/**
 * The timing as entered: either [localDue], a wall-clock time in [timeZone], or a [bucket] picked on today's date in
 * [timeZone] (the zone the user is in). [validate] resolves it with the current instant it is given.
 */
data class TaskTimingInput(
    val timeZone: String,
    val bucket: TimeBucket? = null,
    val localDue: LocalDateTime? = null,
) {
    internal fun validate(
        now: Instant,
        checks: TaskChecks,
    ): TaskTiming? {
        val zone = TaskTiming.zoneOf(timeZone)
        if (zone == null) checks.report(TaskField.TIME_ZONE, TaskProblem.INVALID_TIME_ZONE)
        val kindProblem =
            when {
                bucket == null && localDue == null -> TaskProblem.REQUIRED
                bucket != null && localDue != null -> TaskProblem.AMBIGUOUS
                else -> null
            }
        checks.report(TaskField.TIMING, kindProblem)
        return if (zone == null || kindProblem != null) null else resolve(now, zone, checks)
    }

    private fun resolve(
        now: Instant,
        zone: ZoneId,
        checks: TaskChecks,
    ): TaskTiming? {
        val (timing, field) =
            if (localDue != null) {
                TaskTiming.Exact.of(localDue, zone) to TaskField.DUE
            } else {
                bucket?.on(LocalDate.ofInstant(now, zone)) to TaskField.TIMING
            }
        if (timing == null) checks.report(field, TaskProblem.OUT_OF_RANGE)
        return timing
    }
}
