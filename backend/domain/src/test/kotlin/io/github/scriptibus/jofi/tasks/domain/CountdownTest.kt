// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class CountdownTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val id = CountdownId(UUID.fromString("00000000-0000-0000-0000-000000000021"))
    private val details = CountdownDetails("Secret notice ends", LocalDate.parse("2026-12-31"))
    private val countdown = Countdown.create(id, details, at)

    @Test
    fun `changelog entries refer to it as a countdown`() {
        id.toEntityRef() shouldBe EntityRef("countdown", id.value.toString())
    }

    @Test
    fun `a new countdown is the first version, an edit the next`() {
        countdown shouldBe Countdown(id, details, 0, at, at)
        countdown.edit(details, at.plusSeconds(1)) shouldBe countdown
        val moved = details.copy(targetDate = LocalDate.parse("2027-01-31"))
        countdown.edit(moved, at.plusSeconds(1)) shouldBe
            countdown.copy(details = moved, version = 1, updatedAt = at.plusSeconds(1))
    }

    @Test
    fun `input is normalized and checked`() {
        CountdownInput(" Notice ", LocalDate.parse("2026-12-31")).validate() shouldBe
            TaskValidation.Valid(CountdownDetails("Notice", LocalDate.parse("2026-12-31")))
        CountdownInput(
            " ",
            LocalDate.parse("2100-01-01"),
        ).validate().shouldBeInstanceOf<TaskValidation.Invalid>().violations shouldBe
            listOf(
                TaskViolation(TaskField.TITLE, TaskProblem.REQUIRED),
                TaskViolation(TaskField.TARGET_DATE, TaskProblem.OUT_OF_RANGE),
            )
        CountdownInput(
            "x".repeat(201),
            LocalDate.parse("1999-12-31"),
        ).validate()
            .shouldBeInstanceOf<TaskValidation.Invalid>()
            .violations shouldBe
            listOf(
                TaskViolation(TaskField.TITLE, TaskProblem.TOO_LONG),
                TaskViolation(TaskField.TARGET_DATE, TaskProblem.OUT_OF_RANGE),
            )
    }

    @Test
    fun `exactly the limits are valid, a past date too`() {
        val longest = "x".repeat(CountdownDetails.MAX_TITLE_LENGTH)
        listOf(TaskTiming.EARLIEST_DAY, TaskTiming.LATEST_DAY.minusDays(1), LocalDate.parse("2020-01-01")).forEach {
            CountdownInput(longest, it).validate() shouldBe TaskValidation.Valid(CountdownDetails(longest, it))
        }
    }

    @Test
    fun `invariants hold`() {
        shouldThrow<IllegalArgumentException> { countdown.copy(version = -1) }
        shouldThrow<IllegalArgumentException> { countdown.copy(updatedAt = at.minusSeconds(1)) }
        shouldThrow<IllegalArgumentException> { details.copy(title = "") }
        shouldThrow<IllegalArgumentException> { details.copy(targetDate = TaskTiming.LATEST_DAY) }
    }

    @Test
    fun `nothing personal is printed`() {
        val dashboard =
            DashboardCountdown(
                CountdownKind.NEXT_INTERVIEW,
                "Secret title",
                CountdownTarget.At(at, ZoneId.of("Europe/Berlin")),
                EntityRef("interview", "f1"),
            )

        listOf(countdown, details, CountdownInput("Secret", details.targetDate), dashboard).forEach {
            it.toString() shouldNotContain "Secret"
        }
    }
}
