// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.job.CronSchedule
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.github.scriptibus.jofi.shared.domain.job.RecurringJobId
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The Ghosted suggestion (spec §6.2, #85): once a day, every application without an answer for the Ghosted period
 * gets a suggested task "Mark as Ghosted" linked to it. It never changes a status: the user accepts by moving the
 * application to `GHOSTED` (as `Actor.User`, through the status change of #84) or dismisses the suggestion. The rule
 * name is also the job type and the changelog actor of what the job does.
 */
object GhostedSuggestion {
    const val RULE = "ghosted-suggestion"
    val TYPE = JobType(RULE)
    val RECURRING_ID = RecurringJobId(RULE)
    val REQUEST = JobRequest(TYPE)
    val ACTOR = Actor.System(RULE)

    /**
     * Daily at 04:00 UTC plus a random delay of up to 15 minutes (tech-stack proposal §7). The hour does not matter,
     * a silence of weeks does not depend on it, so UTC needs no user time zone.
     */
    val SCHEDULE = CronSchedule("0 4 * * *", ZoneOffset.UTC, Duration.ofMinutes(MAX_DELAY_MINUTES))

    private const val MAX_DELAY_MINUTES = 15L
    private const val TITLE_PREFIX = "Mark as Ghosted: "

    /**
     * The suggestion's identity: the application and the last activity its silence started with. The same silence
     * is suggested once (and never again once dismissed); a new silence after new activity is a new suggestion.
     */
    fun origin(
        application: UUID,
        silentSince: Instant,
    ): TaskOrigin.Suggested = TaskOrigin.Suggested(RULE, "application:$application:$silentSince")

    /**
     * The suggested task for the application with [title]: no due date (the silence has no deadline), linked to the
     * application. A long title is shortened to fit, never between the halves of a surrogate pair.
     */
    fun details(
        application: UUID,
        title: String,
    ): TaskDetails {
        val room = TaskDetails.MAX_TITLE_LENGTH - TITLE_PREFIX.length
        val shortened = if (title.length <= room) title else title.take(room).dropLastWhile { it.isHighSurrogate() }
        return TaskDetails(
            title = TITLE_PREFIX + shortened.trimEnd(),
            timing = TaskTiming.Bucket.SOMEDAY,
            link = ApplicationRef(application),
        )
    }
}

/** Outcome of one Ghosted suggestion run: how many suggestions it made and how many obsolete ones it dismissed. */
data class GhostedSuggestionRun(
    val suggested: Int,
    val dismissed: Int,
)
