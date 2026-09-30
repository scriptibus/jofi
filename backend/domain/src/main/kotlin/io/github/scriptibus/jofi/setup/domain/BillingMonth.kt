// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * The calendar month AI costs are summed and the monthly budget is counted in. Months run in UTC,
 * like the providers' own usage reports, so a month's total never depends on the server's time
 * zone; the budget pause lifts at 00:00 UTC on the first of the month.
 */
data class BillingMonth(
    val month: YearMonth,
) {
    /** The first instant of the month. */
    val start: Instant get() = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)

    /** The first instant of the next month (exclusive end). */
    val end: Instant get() =
        month
            .plusMonths(1)
            .atDay(1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)

    /** The month [count] months before this one. */
    fun minus(count: Long): BillingMonth = BillingMonth(month.minusMonths(count))

    /** The month as `YYYY-MM`, as the API names it. */
    override fun toString(): String = month.toString()

    companion object {
        /** The first month the cost reports accept: AI costs cannot be older, and it keeps queries in range. */
        val EARLIEST: BillingMonth = BillingMonth(YearMonth.of(2000, 1))

        private const val MONTH_TEXT_LENGTH = 7

        fun of(instant: Instant): BillingMonth = BillingMonth(YearMonth.from(instant.atOffset(ZoneOffset.UTC)))

        /**
         * The month named by [text] (`YYYY-MM`) for a cost report, if it lies between [EARLIEST] and
         * [current]: a future month has no costs yet.
         */
        fun parse(
            text: String,
            current: BillingMonth,
        ): SetupValidation<BillingMonth> {
            val parsed = if (text.length == MONTH_TEXT_LENGTH) parseOrNull(text) else null
            return when {
                parsed == null -> {
                    invalid(SetupViolationKind.INVALID_FORMAT)
                }

                parsed.month < EARLIEST.month || parsed.month > current.month -> {
                    invalid(
                        SetupViolationKind.OUT_OF_RANGE,
                    )
                }

                else -> {
                    SetupValidation.Valid(parsed)
                }
            }
        }

        private fun parseOrNull(text: String): BillingMonth? =
            try {
                BillingMonth(YearMonth.parse(text))
            } catch (_: DateTimeParseException) {
                null
            }

        private fun invalid(kind: SetupViolationKind) =
            SetupValidation.Invalid(listOf(SetupViolation(SetupField.MONTH, kind)))
    }
}
