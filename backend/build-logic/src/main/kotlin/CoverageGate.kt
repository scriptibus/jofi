// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** Coverage thresholds shared by the Kover convention. */
object CoverageGate {
    /** Minimum line coverage for `domain` and `application`. Raise it, never lower it. */
    const val MIN_LINE_COVERAGE_PERCENT: Int = 70
}
