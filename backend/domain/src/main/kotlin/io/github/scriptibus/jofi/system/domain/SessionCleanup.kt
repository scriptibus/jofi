// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import io.github.scriptibus.jofi.shared.domain.job.CronSchedule
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.github.scriptibus.jofi.shared.domain.job.RecurringJobId
import java.time.ZoneOffset

/**
 * Housekeeping in the worker: every hour it deletes login sessions whose idle timeout has passed.
 * Such sessions are refused anyway; deleting them keeps the session table small and ends bearer
 * credentials that would otherwise stay in the database.
 */
object SessionCleanup {
    val TYPE = JobType("session-cleanup")
    val RECURRING_ID = RecurringJobId("session-cleanup")
    val REQUEST = JobRequest(TYPE)

    /** At minute 0 of every hour; UTC, because the time of day does not matter. */
    val SCHEDULE = CronSchedule("0 * * * *", ZoneOffset.UTC)

    /** The changelog actor of the deletions: the job's name. */
    val ACTOR_NAME = TYPE.name
}

/** Outcome of one cleanup run. */
sealed interface SessionCleanupResult {
    /** [deleted] expired sessions are gone (zero when there were none). */
    data class Cleaned(
        val deleted: Int,
    ) : SessionCleanupResult

    data object StorageFailure : SessionCleanupResult
}
