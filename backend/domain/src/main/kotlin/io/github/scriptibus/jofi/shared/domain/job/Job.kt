// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.job

import java.time.Duration
import java.time.ZoneId
import java.util.UUID

/** Identifies one enqueued background job. */
@JvmInline
value class JobId(
    val value: UUID,
)

/**
 * Background work to run in the worker (ADR-0010): which handler ([type]) with which [arguments].
 * Arguments are ids and small settings only, never secrets or personal data, because the job
 * store and the job log keep them (spec §13, threat model T4).
 */
data class JobRequest(
    val type: JobType,
    val arguments: Map<String, String> = emptyMap(),
) {
    init {
        require(arguments.keys.none { it.isBlank() }) { "Job argument names must not be blank" }
    }
}

/** The name of a registered job handler, e.g. `scanner-run`. Also the actor name in the changelog. */
@JvmInline
value class JobType(
    val name: String,
) {
    init {
        require(SLUG.matches(name)) { "A job type must be a lower-case slug" }
    }
}

/** Stable id of a recurring schedule, e.g. `scanner-bundesagentur`; scheduling it again replaces it. */
@JvmInline
value class RecurringJobId(
    val value: String,
) {
    init {
        require(SLUG.matches(value)) { "A recurring job id must be a lower-case slug" }
    }
}

/**
 * When a recurring job runs: a five-field cron [expression] (minute hour day-of-month month
 * day-of-week) evaluated in [zone], so "every day at 07:00" follows the user's clock. The job
 * adapter validates the field syntax; this type guards the shape.
 *
 * Each run starts after a random delay between zero and [maxRandomDelay], so a source is never hit
 * at an exact, predictable time (proposal §7: scanners wait up to 15 minutes).
 */
data class CronSchedule(
    val expression: String,
    val zone: ZoneId,
    val maxRandomDelay: Duration = Duration.ZERO,
) {
    init {
        require(expression.trim().split(WHITESPACE).size == CRON_FIELDS) { "A cron expression has $CRON_FIELDS fields" }
        require(!maxRandomDelay.isNegative) { "A random delay must not be negative" }
        require(maxRandomDelay <= MAX_RANDOM_DELAY) { "A random delay must be at most $MAX_RANDOM_DELAY" }
    }

    companion object {
        /** A longer delay would let a run of an hourly schedule drift past the next slot. */
        val MAX_RANDOM_DELAY: Duration = Duration.ofHours(1)

        private const val CRON_FIELDS = 5
        private val WHITESPACE = Regex("\\s+")
    }
}

/** Outcome of a job-scheduler call; failures are values, not exceptions. */
sealed interface JobResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : JobResult<T>

    /** The job or schedule to cancel does not exist (any more). */
    data object NotFound : JobResult<Nothing>

    /** The cron expression has five fields but is not valid, e.g. `61 * * * *`. */
    data object InvalidSchedule : JobResult<Nothing>

    /** The job store could not complete [operation]. Carries no arguments, so it is safe to log. */
    data class StorageFailure(
        val operation: String,
    ) : JobResult<Nothing>
}

internal val SLUG = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
