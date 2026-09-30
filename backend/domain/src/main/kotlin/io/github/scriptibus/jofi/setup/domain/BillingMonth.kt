// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

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

    companion object {
        fun of(instant: Instant): BillingMonth = BillingMonth(YearMonth.from(instant.atOffset(ZoneOffset.UTC)))
    }
}
