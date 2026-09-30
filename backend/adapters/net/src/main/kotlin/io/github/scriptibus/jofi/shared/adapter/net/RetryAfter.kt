// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Parses `Retry-After` (RFC 9110 §10.2.3): delay seconds or an HTTP date. */
internal object RetryAfter {
    private val DELAY_SECONDS = Regex("\\d{1,9}")

    /** The delay, never negative; null when the header is absent or malformed. */
    fun parse(
        value: String?,
        clock: Clock,
    ): Duration? {
        val trimmed = value?.trim()
        return when {
            trimmed == null -> null
            DELAY_SECONDS.matches(trimmed) -> Duration.ofSeconds(trimmed.toLong())
            else -> parseDate(trimmed)?.let { maxOf(Duration.between(clock.instant(), it), Duration.ZERO) }
        }
    }

    private fun parseDate(value: String): Instant? =
        try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
}
