// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The groups of the task list on the viewer's calendar (ADR-0049), with fixed "now"s: 30 September 2026 is a
 * Wednesday, 28 September and 5 October are Mondays. Timings are written `EXACT <instant>`, `<span> <first day>` or
 * `SOMEDAY`.
 */
class TaskCalendarTest {
    @ParameterizedTest(name = "{2} at {0} in {1} is {3}")
    @CsvSource(
        // An exact time is overdue once passed, else grouped by its day in the viewer's zone.
        "2026-09-30T10:00:00Z, Europe/Berlin, EXACT 2026-09-30T09:59:59Z, OVERDUE",
        "2026-09-30T10:00:00Z, Europe/Berlin, EXACT 2026-09-30T10:00:00Z, TODAY",
        "2026-09-30T10:00:00Z, Europe/Berlin, EXACT 2026-09-30T21:30:00Z, TODAY",
        "2026-09-30T10:00:00Z, Europe/Berlin, EXACT 2026-09-30T22:30:00Z, THIS_WEEK",
        "2026-09-30T10:00:00Z, UTC, EXACT 2026-09-30T22:30:00Z, TODAY",
        "2026-09-30T10:00:00Z, America/New_York, EXACT 2026-10-01T03:30:00Z, TODAY",
        // Days: this week runs to Sunday, next week from Monday; this month ends on 30 September.
        "2026-09-30T10:00:00Z, Europe/Berlin, DAY 2026-09-29, OVERDUE",
        "2026-09-30T10:00:00Z, Europe/Berlin, DAY 2026-09-30, TODAY",
        "2026-09-30T10:00:00Z, Europe/Berlin, DAY 2026-10-04, THIS_WEEK",
        "2026-09-30T10:00:00Z, Europe/Berlin, DAY 2026-10-05, NEXT_WEEK",
        "2026-09-30T10:00:00Z, Europe/Berlin, DAY 2026-10-11, NEXT_WEEK",
        "2026-09-30T10:00:00Z, Europe/Berlin, DAY 2026-10-12, LATER",
        // Weeks and months that have begun are due now; later ones are grouped by their first day.
        "2026-09-30T10:00:00Z, Europe/Berlin, WEEK 2026-09-21, OVERDUE",
        "2026-09-30T10:00:00Z, Europe/Berlin, WEEK 2026-09-28, THIS_WEEK",
        "2026-09-30T10:00:00Z, Europe/Berlin, WEEK 2026-10-05, NEXT_WEEK",
        "2026-09-30T10:00:00Z, Europe/Berlin, WEEK 2026-10-12, LATER",
        "2026-09-30T10:00:00Z, Europe/Berlin, MONTH 2026-08-01, OVERDUE",
        "2026-09-30T10:00:00Z, Europe/Berlin, MONTH 2026-09-01, THIS_MONTH",
        "2026-09-30T10:00:00Z, Europe/Berlin, MONTH 2026-10-01, LATER",
        "2026-09-30T10:00:00Z, Europe/Berlin, SOMEDAY, SOMEDAY",
        // Monday 14 September: the rest of the month after next week is this month.
        "2026-09-14T08:00:00Z, Europe/Berlin, WEEK 2026-09-07, OVERDUE",
        "2026-09-14T08:00:00Z, Europe/Berlin, WEEK 2026-09-14, THIS_WEEK",
        "2026-09-14T08:00:00Z, Europe/Berlin, DAY 2026-09-14, TODAY",
        "2026-09-14T08:00:00Z, Europe/Berlin, DAY 2026-09-20, THIS_WEEK",
        "2026-09-14T08:00:00Z, Europe/Berlin, DAY 2026-09-21, NEXT_WEEK",
        "2026-09-14T08:00:00Z, Europe/Berlin, DAY 2026-09-28, THIS_MONTH",
        "2026-09-14T08:00:00Z, Europe/Berlin, WEEK 2026-09-28, THIS_MONTH",
        "2026-09-14T08:00:00Z, Europe/Berlin, DAY 2026-10-01, LATER",
        // Midnight has passed in Berlin but not in UTC: a new day, a new week, a new month.
        "2026-09-29T22:30:00Z, Europe/Berlin, DAY 2026-09-29, OVERDUE",
        "2026-09-29T22:30:00Z, UTC, DAY 2026-09-29, TODAY",
        "2026-10-04T22:30:00Z, Europe/Berlin, WEEK 2026-09-28, OVERDUE",
        "2026-10-04T22:30:00Z, UTC, WEEK 2026-09-28, THIS_WEEK",
        "2026-10-04T22:30:00Z, Europe/Berlin, WEEK 2026-10-05, THIS_WEEK",
        "2026-10-04T22:30:00Z, UTC, WEEK 2026-10-05, NEXT_WEEK",
        "2026-10-31T23:30:00Z, Europe/Berlin, MONTH 2026-10-01, OVERDUE",
        "2026-10-31T23:30:00Z, UTC, MONTH 2026-10-01, THIS_MONTH",
        "2026-10-31T23:30:00Z, Europe/Berlin, MONTH 2026-11-01, THIS_MONTH",
        "2026-10-31T23:30:00Z, UTC, MONTH 2026-11-01, LATER",
        // Clocks go back on Sunday 25 October (UTC+2 to UTC+1): 22:30Z is 23:30 on Sunday, 23:30Z is Monday.
        "2026-10-19T10:00:00Z, Europe/Berlin, EXACT 2026-10-25T22:30:00Z, THIS_WEEK",
        "2026-10-19T10:00:00Z, Europe/Berlin, EXACT 2026-10-25T23:30:00Z, NEXT_WEEK",
        "2026-10-25T22:30:00Z, Europe/Berlin, DAY 2026-10-25, TODAY",
        "2026-10-25T22:30:00Z, Europe/Berlin, EXACT 2026-10-25T23:30:00Z, NEXT_WEEK",
        // Clocks go forward on Sunday 29 March (UTC+1 to UTC+2): 21:30Z is 23:30 on Sunday, 22:30Z is Monday.
        "2026-03-29T21:30:00Z, Europe/Berlin, WEEK 2026-03-23, THIS_WEEK",
        "2026-03-29T22:30:00Z, Europe/Berlin, WEEK 2026-03-23, OVERDUE",
        "2026-03-29T22:30:00Z, Europe/Berlin, DAY 2026-03-30, TODAY",
    )
    fun `groups a timing on the viewer's calendar`(
        now: String,
        zone: String,
        timing: String,
        expected: TaskGroupKind,
    ) {
        TaskCalendar(Instant.parse(now), ZoneId.of(zone)).kindOf(timingOf(timing)) shouldBe expected
    }

    @Test
    fun `lists every group in order, soonest first, oldest first on the same deadline`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val week = task(timingOf("WEEK 2026-09-28"), 1)
        val day = task(timingOf("DAY 2026-10-01"), 2)
        val exact = task(TaskTiming.Exact(Instant.parse("2026-10-01T08:00:00Z"), berlin), 3)
        val sameDayOlder = task(timingOf("DAY 2026-10-01"), 0)
        val someday = task(TaskTiming.Bucket.SOMEDAY, 5)
        val olderSomeday = task(TaskTiming.Bucket.SOMEDAY, 4)

        val groups =
            TaskCalendar(Instant.parse("2026-09-30T10:00:00Z"), berlin)
                .group(listOf(week, day, someday, exact, sameDayOlder, olderSomeday))

        groups.map { it.kind } shouldContainExactly TaskGroupKind.entries
        groups.single { it.kind == TaskGroupKind.THIS_WEEK }.tasks shouldContainExactly
            listOf(exact, sameDayOlder, day, week)
        groups.single { it.kind == TaskGroupKind.SOMEDAY }.tasks shouldContainExactly listOf(olderSomeday, someday)
        groups
            .filter { it.kind != TaskGroupKind.THIS_WEEK && it.kind != TaskGroupKind.SOMEDAY }
            .forEach { it.tasks shouldBe emptyList() }
    }

    private fun task(
        timing: TaskTiming,
        createdMinute: Long,
    ): Task =
        Task.create(
            TaskId(UUID.randomUUID()),
            TaskDetails("Task", timing),
            TaskOrigin.Manual,
            Instant.parse("2026-09-01T08:00:00Z").plusSeconds(createdMinute * 60),
        )

    private fun timingOf(text: String): TaskTiming {
        val parts = text.split(' ')
        return when (parts[0]) {
            "SOMEDAY" -> TaskTiming.Bucket.SOMEDAY
            "EXACT" -> TaskTiming.Exact(Instant.parse(parts[1]), ZoneId.of("UTC"))
            else -> TaskTiming.Bucket(BucketSpan.valueOf(parts[0]), LocalDate.parse(parts[1]))
        }
    }
}
