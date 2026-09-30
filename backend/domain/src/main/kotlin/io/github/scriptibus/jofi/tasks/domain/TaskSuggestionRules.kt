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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The rules of spec §10.2 (#95): follow up after applying, prepare for an interview the day before, answer an offer
 * before its deadline. One job, `task-suggestions`, runs them together: once a day (the follow-up is due after a
 * number of days, and a deadline passes without an event) and whenever an application's status changes or an
 * interview is logged or rescheduled. Each rule names its suggestions' actor (`Actor.System(<rule>)`).
 */
object TaskSuggestionRules {
    private const val JOB = "task-suggestions"
    val TYPE = JobType(JOB)
    val RECURRING_ID = RecurringJobId(JOB)
    val REQUEST = JobRequest(TYPE)

    /** Daily at 04:30 UTC plus a random delay of up to 15 minutes (tech-stack proposal §7), after the Ghosted run. */
    val SCHEDULE = CronSchedule("30 4 * * *", ZoneOffset.UTC, Duration.ofMinutes(MAX_DELAY_MINUTES))

    /** The rules this job suggests for; it dismisses only their obsolete suggestions. */
    val RULES: Set<String>
        get() = setOf(FollowUpSuggestion.RULE, InterviewPreparationSuggestion.RULE, OfferAnswerSuggestion.RULE)

    private const val MAX_DELAY_MINUTES = 15L
}

/**
 * Follow up on an application that is `APPLIED` with no answer for the follow-up period (the settings, ADR-0050). Its
 * key is the application and the activity its silence started with, like the Ghosted suggestion's: one silence is one
 * suggestion. It is due on the day the period ended, as a date in UTC (Jofi keeps no zone of the user, ADR-0049).
 */
object FollowUpSuggestion {
    const val RULE = "follow-up"
    val ACTOR = Actor.System(RULE)
    private const val TITLE_PREFIX = "Follow up: "

    fun origin(
        application: UUID,
        silentSince: Instant,
    ): TaskOrigin.Suggested = TaskOrigin.Suggested(RULE, "application:$application:$silentSince")

    fun details(
        application: UUID,
        title: String,
        dueAt: Instant,
    ): TaskDetails {
        val day = dueAt.atZone(ZoneOffset.UTC).toLocalDate()
        return TaskDetails(suggestionTitle(TITLE_PREFIX, title), dayBucket(day), ApplicationRef(application))
    }
}

/**
 * Prepare for an interview still to come, due the day before it on the calendar of the zone it was planned in
 * (ADR-0048). Its key is the interview and that day: moving the interview to another day makes a new suggestion and
 * the old one obsolete, moving it within the day keeps it.
 */
object InterviewPreparationSuggestion {
    const val RULE = "interview-preparation"
    val ACTOR = Actor.System(RULE)
    private const val TITLE_PREFIX = "Prepare for the interview: "

    /** The day before the interview starting [startsAt], on the clocks of [zone]. */
    fun dayBefore(
        startsAt: Instant,
        zone: ZoneId,
    ): LocalDate = startsAt.atZone(zone).toLocalDate().minusDays(1)

    fun origin(
        interview: UUID,
        day: LocalDate,
    ): TaskOrigin.Suggested = TaskOrigin.Suggested(RULE, "interview:$interview:$day")

    fun details(
        application: UUID,
        title: String,
        day: LocalDate,
    ): TaskDetails = TaskDetails(suggestionTitle(TITLE_PREFIX, title), dayBucket(day), ApplicationRef(application))
}

/**
 * Answer an offer by the date the company named, due the day before it. Its key is the application and the date: a
 * new date makes a new suggestion and the old one obsolete.
 */
object OfferAnswerSuggestion {
    const val RULE = "offer-answer"
    val ACTOR = Actor.System(RULE)
    private const val TITLE_PREFIX = "Answer the offer: "

    fun origin(
        application: UUID,
        answerBy: LocalDate,
    ): TaskOrigin.Suggested = TaskOrigin.Suggested(RULE, "application:$application:$answerBy")

    fun details(
        application: UUID,
        title: String,
        answerBy: LocalDate,
    ): TaskDetails =
        TaskDetails(suggestionTitle(TITLE_PREFIX, title), dayBucket(answerBy.minusDays(1)), ApplicationRef(application))
}

/** A day bucket on [day], kept within the range a bucket may start in. */
private fun dayBucket(day: LocalDate): TaskTiming.Bucket =
    TaskTiming.Bucket(BucketSpan.DAY, day.coerceIn(TaskTiming.EARLIEST_DAY, TaskTiming.LATEST_DAY.minusDays(1)))
