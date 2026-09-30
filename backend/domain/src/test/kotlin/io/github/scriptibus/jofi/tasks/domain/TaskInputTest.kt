// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class TaskInputTest {
    // Wednesday 2026-09-30, 23:30 in UTC: already Thursday 2026-10-01 in Berlin.
    private val now = Instant.parse("2026-09-30T23:30:00Z")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val input = TaskInput("Call back", TaskTimingInput("Europe/Berlin", bucket = TimeBucket.TODAY))

    private fun valid(input: TaskInput): TaskDetails =
        input.validate(now).shouldBeInstanceOf<TaskValidation.Valid<TaskDetails>>().value

    private fun violations(input: TaskInput): List<TaskViolation> =
        input.validate(now).shouldBeInstanceOf<TaskValidation.Invalid>().violations

    private fun bucket(
        bucket: TimeBucket,
        zone: String = "Europe/Berlin",
    ): TaskTiming = valid(input.copy(timing = TaskTimingInput(zone, bucket = bucket))).timing

    @Test
    fun `a bucket is resolved on today's date in the user's zone, weeks from Monday`() {
        bucket(TimeBucket.TODAY) shouldBe TaskTiming.Bucket(BucketSpan.DAY, LocalDate.parse("2026-10-01"))
        bucket(TimeBucket.TODAY, "UTC") shouldBe TaskTiming.Bucket(BucketSpan.DAY, LocalDate.parse("2026-09-30"))
        bucket(TimeBucket.THIS_WEEK) shouldBe TaskTiming.Bucket(BucketSpan.WEEK, LocalDate.parse("2026-09-28"))
        bucket(TimeBucket.NEXT_WEEK) shouldBe TaskTiming.Bucket(BucketSpan.WEEK, LocalDate.parse("2026-10-05"))
        bucket(TimeBucket.THIS_MONTH) shouldBe TaskTiming.Bucket(BucketSpan.MONTH, LocalDate.parse("2026-10-01"))
        bucket(TimeBucket.THIS_MONTH, "UTC") shouldBe TaskTiming.Bucket(BucketSpan.MONTH, LocalDate.parse("2026-09-01"))
        bucket(TimeBucket.SOMEDAY) shouldBe TaskTiming.Bucket.SOMEDAY
    }

    @Test
    fun `on a Monday this week starts today`() {
        TimeBucket.THIS_WEEK.on(LocalDate.parse("2026-10-05")) shouldBe
            TaskTiming.Bucket(BucketSpan.WEEK, LocalDate.parse("2026-10-05"))
        TimeBucket.NEXT_WEEK.on(LocalDate.parse("2026-10-04")) shouldBe
            TaskTiming.Bucket(BucketSpan.WEEK, LocalDate.parse("2026-10-05"))
    }

    @Test
    fun `an exact due time is the wall-clock time in its zone, to the microsecond`() {
        val local = LocalDateTime.parse("2026-10-05T10:00:00.123456789")
        val timing = valid(input.copy(timing = TaskTimingInput(" Europe/Berlin ", localDue = local))).timing

        timing shouldBe TaskTiming.Exact(Instant.parse("2026-10-05T08:00:00.123456Z"), berlin)
        (timing as TaskTiming.Exact).localDue shouldBe LocalDateTime.parse("2026-10-05T10:00:00.123456")
    }

    @Test
    fun `text is normalized and blank notes are absent`() {
        val link = CompanyRef(UUID.randomUUID())
        val details = valid(input.copy(title = " Café ", notes = "  ", link = link))

        details.title shouldBe "Café"
        details.notes shouldBe null
        details.link shouldBe link
    }

    @Test
    fun `a timing needs exactly one of a due time and a bucket`() {
        violations(input.copy(timing = TaskTimingInput("UTC"))) shouldBe
            listOf(TaskViolation(TaskField.TIMING, TaskProblem.REQUIRED))
        val both = TaskTimingInput("UTC", TimeBucket.TODAY, LocalDateTime.parse("2026-10-05T10:00"))
        violations(input.copy(timing = both)) shouldBe listOf(TaskViolation(TaskField.TIMING, TaskProblem.AMBIGUOUS))
    }

    @Test
    fun `an unknown zone, a due time out of range and a bucket out of range are reported`() {
        listOf("", "Europe/Nowhere", "europe/berlin", "x".repeat(TaskTiming.MAX_ZONE_ID_LENGTH + 1)).forEach {
            violations(input.copy(timing = TaskTimingInput(it, bucket = TimeBucket.TODAY))) shouldBe
                listOf(TaskViolation(TaskField.TIME_ZONE, TaskProblem.INVALID_TIME_ZONE))
        }
        listOf("1999-12-31T23:59:59", "2100-01-01T00:00").forEach {
            violations(input.copy(timing = TaskTimingInput("UTC", localDue = LocalDateTime.parse(it)))) shouldBe
                listOf(TaskViolation(TaskField.DUE, TaskProblem.OUT_OF_RANGE))
        }
        TimeBucket.NEXT_WEEK.on(LocalDate.parse("2099-12-31")) shouldBe null
        TimeBucket.THIS_WEEK.on(LocalDate.parse("2000-01-01")) shouldBe null
        input
            .copy(timing = TaskTimingInput("UTC", bucket = TimeBucket.NEXT_WEEK))
            .validate(Instant.parse("2099-12-31T12:00:00Z"))
            .shouldBeInstanceOf<TaskValidation.Invalid>()
            .violations shouldBe listOf(TaskViolation(TaskField.TIMING, TaskProblem.OUT_OF_RANGE))
    }

    @Test
    fun `every problem is reported at once`() {
        val broken =
            TaskInput(" ", TaskTimingInput("Mars/Olympus", bucket = TimeBucket.TODAY), notes = "x".repeat(10_001))

        violations(broken) shouldBe
            listOf(
                TaskViolation(TaskField.TITLE, TaskProblem.REQUIRED),
                TaskViolation(TaskField.TIME_ZONE, TaskProblem.INVALID_TIME_ZONE),
                TaskViolation(TaskField.NOTES, TaskProblem.TOO_LONG),
            )
        violations(input.copy(title = "a\u0000b")) shouldBe
            listOf(TaskViolation(TaskField.TITLE, TaskProblem.INVALID_CHARACTER))
    }

    @Test
    fun `exactly the limits are valid`() {
        val title = "x".repeat(TaskDetails.MAX_TITLE_LENGTH)
        val notes = "x".repeat(TaskDetails.MAX_NOTES_LENGTH)
        val earliest = TaskTimingInput("UTC", localDue = LocalDateTime.parse("2000-01-01T00:00"))

        valid(input.copy(title = title, notes = notes, timing = earliest)).timing shouldBe
            TaskTiming.Exact(TaskTiming.EARLIEST, ZoneId.of("UTC"))
    }

    @Test
    fun `timing invariants hold`() {
        shouldThrow<IllegalArgumentException> {
            TaskTiming.Exact(
                Instant.parse("2026-10-05T08:00:00Z").plusNanos(1),
                berlin,
            )
        }
        shouldThrow<IllegalArgumentException> { TaskTiming.Exact(TaskTiming.LATEST, berlin) }
        shouldThrow<IllegalArgumentException> { TaskTiming.Bucket(BucketSpan.SOMEDAY, LocalDate.parse("2026-10-05")) }
        shouldThrow<IllegalArgumentException> { TaskTiming.Bucket(BucketSpan.DAY, null) }
        shouldThrow<IllegalArgumentException> { TaskTiming.Bucket(BucketSpan.WEEK, LocalDate.parse("2026-10-06")) }
        shouldThrow<IllegalArgumentException> { TaskTiming.Bucket(BucketSpan.MONTH, LocalDate.parse("2026-10-02")) }
        shouldThrow<IllegalArgumentException> { TaskTiming.Bucket(BucketSpan.DAY, TaskTiming.LATEST_DAY) }
        shouldThrow<IllegalArgumentException> { TaskTiming.Bucket(BucketSpan.DAY, LocalDate.parse("1999-12-31")) }
    }

    @Test
    fun `a bucket ends on the day after it`() {
        TaskTiming.Bucket(BucketSpan.DAY, LocalDate.parse("2026-10-31")).endsBefore shouldBe
            LocalDate.parse("2026-11-01")
        TaskTiming.Bucket(BucketSpan.WEEK, LocalDate.parse("2026-09-28")).endsBefore shouldBe
            LocalDate.parse("2026-10-05")
        TaskTiming.Bucket(BucketSpan.MONTH, LocalDate.parse("2026-02-01")).endsBefore shouldBe
            LocalDate.parse("2026-03-01")
        TaskTiming.Bucket.SOMEDAY.endsBefore shouldBe null
    }

    @Test
    fun `the input prints neither title nor notes`() {
        input.copy(title = "Secret", notes = "Secret").toString() shouldNotContain "Secret"
    }
}
