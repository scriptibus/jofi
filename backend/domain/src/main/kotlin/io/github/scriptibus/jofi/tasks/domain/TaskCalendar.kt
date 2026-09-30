// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The viewer's calendar at [now] in [zone] (ADR-0049): which [TaskGroupKind] a task falls in, weeks from Monday.
 *
 * An exact time is overdue once it has passed, otherwise grouped by its day on the viewer's clocks. A bucket is
 * overdue from the day after it ends. A bucket that has begun but not ended groups by its span (a day is today, a week
 * this week, a month this month), since it is due now rather than on its first day; a later week groups by its Monday
 * like a day, a later month is later.
 */
class TaskCalendar(
    private val now: Instant,
    private val zone: ZoneId,
) {
    private val today: LocalDate = LocalDate.ofInstant(now, zone)
    private val nextMonday: LocalDate = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    private val mondayAfterNext: LocalDate = nextMonday.plusWeeks(1)
    private val nextMonth: LocalDate = today.withDayOfMonth(1).plusMonths(1)

    private val soonestFirst: Comparator<Task> =
        compareBy<Task, Instant?>(nullsLast()) { deadline(it.details.timing) }
            .thenBy { it.createdAt }
            .thenBy { it.id.value }

    /**
     * Every group of [TaskGroupKind] in its order, empty ones included, each with its [tasks] soonest first: by the
     * end of their timing (an exact time, or the start of the day after a bucket), then oldest first.
     */
    fun group(tasks: List<Task>): List<TaskGroup> {
        val byKind = tasks.sortedWith(soonestFirst).groupBy { kindOf(it.details.timing) }
        return TaskGroupKind.entries.map { TaskGroup(it, byKind[it].orEmpty()) }
    }

    fun kindOf(timing: TaskTiming): TaskGroupKind =
        when (timing) {
            is TaskTiming.Exact -> kindOfExact(timing.dueAt)
            is TaskTiming.Bucket -> kindOfBucket(timing)
        }

    private fun kindOfExact(dueAt: Instant): TaskGroupKind =
        if (dueAt.isBefore(now)) TaskGroupKind.OVERDUE else kindOfDay(LocalDate.ofInstant(dueAt, zone))

    private fun kindOfBucket(bucket: TaskTiming.Bucket): TaskGroupKind {
        val start = bucket.startsOn ?: return TaskGroupKind.SOMEDAY
        val begun = !start.isAfter(today)
        return when {
            bucket.endsBefore?.isAfter(today) != true -> TaskGroupKind.OVERDUE
            bucket.span == BucketSpan.WEEK && begun -> TaskGroupKind.THIS_WEEK
            bucket.span == BucketSpan.MONTH -> if (begun) TaskGroupKind.THIS_MONTH else TaskGroupKind.LATER
            else -> kindOfDay(start)
        }
    }

    /** The group of a [day] that is today or later. */
    private fun kindOfDay(day: LocalDate): TaskGroupKind =
        when {
            !day.isAfter(today) -> TaskGroupKind.TODAY
            day.isBefore(nextMonday) -> TaskGroupKind.THIS_WEEK
            day.isBefore(mondayAfterNext) -> TaskGroupKind.NEXT_WEEK
            day.isBefore(nextMonth) -> TaskGroupKind.THIS_MONTH
            else -> TaskGroupKind.LATER
        }

    /** When the task is due at the latest; `null` (last) for someday. */
    private fun deadline(timing: TaskTiming): Instant? =
        when (timing) {
            is TaskTiming.Exact -> timing.dueAt
            is TaskTiming.Bucket -> timing.endsBefore?.atStartOfDay(zone)?.toInstant()
        }
}
