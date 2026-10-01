// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.text.textProblem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Identifies one custom countdown. */
@JvmInline
value class CountdownId(
    val value: UUID,
) {
    /** How changelog entries refer to this countdown (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of custom countdowns; never rename it, stored entries use it. */
        const val ENTITY_TYPE = "countdown"
    }
}

/**
 * A custom countdown's [title] and the [targetDate] it counts down to (spec §10.1), e.g. the day the notice period
 * ends. A date without a zone: the days left are counted on the viewer's calendar. A date in the past is kept (the
 * dashboard shows it as reached). [toString] shows no title.
 */
data class CountdownDetails(
    val title: String,
    val targetDate: LocalDate,
) {
    init {
        require(textProblem(title, MAX_TITLE_LENGTH) == null) { "A countdown title breaks an invariant" }
        require(!targetDate.isBefore(TaskTiming.EARLIEST_DAY) && targetDate.isBefore(TaskTiming.LATEST_DAY)) {
            "A countdown target is out of range"
        }
    }

    override fun toString(): String = "CountdownDetails(targetDate=$targetDate)"

    companion object {
        const val MAX_TITLE_LENGTH = 200
    }
}

/**
 * A countdown the user set up for the dashboard (spec §10.1, "custom countdowns"); the others (next interview,
 * deadlines) are derived from what other contexts store and never stored here. [version] counts changes (ADR-0041).
 */
data class Countdown(
    val id: CountdownId,
    val details: CountdownDetails,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(version >= INITIAL_VERSION) { "A countdown version must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "A countdown cannot be updated before it was created" }
    }

    /** The countdown with new [details], changed [at]; the same countdown if they are unchanged. */
    fun edit(
        details: CountdownDetails,
        at: Instant,
    ): Countdown = if (details == this.details) this else copy(details = details, version = version + 1, updatedAt = at)

    companion object {
        const val INITIAL_VERSION = 0L

        /** The confirmable operation (ADR-0039) of deleting custom countdowns; its targets are countdown ids. */
        const val DELETE_OPERATION = "countdowns.delete"

        /** A new countdown with [details], created [at]. */
        fun create(
            id: CountdownId,
            details: CountdownDetails,
            at: Instant,
        ): Countdown = Countdown(id, details, INITIAL_VERSION, at, at)
    }
}

/** A countdown as entered. [validate] normalizes the title like every text and checks the date's range. */
data class CountdownInput(
    val title: String,
    val targetDate: LocalDate,
) {
    fun validate(): TaskValidation<CountdownDetails> {
        val checks = TaskChecks()
        val title = checks.text(TaskField.TITLE, title, CountdownDetails.MAX_TITLE_LENGTH, required = true)
        if (targetDate.isBefore(TaskTiming.EARLIEST_DAY) || !targetDate.isBefore(TaskTiming.LATEST_DAY)) {
            checks.report(TaskField.TARGET_DATE, TaskProblem.OUT_OF_RANGE)
        }
        if (title == null || checks.count > 0) return TaskValidation.Invalid(checks.violations)
        return TaskValidation.Valid(CountdownDetails(title, targetDate))
    }

    override fun toString(): String = "CountdownInput(targetDate=$targetDate)"
}

/**
 * What a dashboard countdown counts down to (spec §10.1). Never rename a constant: clients show them. The end of the
 * current employment or notice period comes from the knowledge context (M2).
 */
enum class CountdownKind {
    /** A [Countdown] the user set up. */
    CUSTOM,

    /** The next interview or call still to come. */
    NEXT_INTERVIEW,

    /** An application's deadline, from the posting. */
    APPLICATION_DEADLINE,

    /** The date by which the user has to answer an offer. */
    OFFER_ANSWER_DEADLINE,
}

/** When a dashboard countdown ends: on a day of the viewer's calendar, or at an instant planned in a zone. */
sealed interface CountdownTarget {
    /** When the countdown ends as seen from [viewer]: the start of the day there, or the instant itself. */
    fun endsAt(viewer: ZoneId): Instant

    data class OnDay(
        val date: LocalDate,
    ) : CountdownTarget {
        override fun endsAt(viewer: ZoneId): Instant = date.atStartOfDay(viewer).toInstant()
    }

    data class At(
        val instant: Instant,
        val zone: ZoneId,
    ) : CountdownTarget {
        override fun endsAt(viewer: ZoneId): Instant = instant
    }
}

/**
 * One countdown on the dashboard (#112): its [kind], a [title] to show (the countdown's, or the application's), its
 * [target] and the [subject] it belongs to (a `countdown`, `application` or `interview` entity), so the dashboard can
 * link to it. [applicationId] is the application an interview belongs to (the interview itself has no page), so the
 * dashboard can link to it; other countdowns leave it out. [toString] shows no title.
 */
data class DashboardCountdown(
    val kind: CountdownKind,
    val title: String,
    val target: CountdownTarget,
    val subject: EntityRef,
    val applicationId: UUID? = null,
) {
    override fun toString(): String = "DashboardCountdown(kind=$kind, target=$target, subject=$subject)"

    companion object {
        /** [countdown] on the dashboard: it ends on its target day. */
        fun of(countdown: Countdown): DashboardCountdown =
            DashboardCountdown(
                CountdownKind.CUSTOM,
                countdown.details.title,
                CountdownTarget.OnDay(countdown.details.targetDate),
                countdown.id.toEntityRef(),
            )

        /**
         * Soonest first as seen from [viewer]: a day counts from its start on the viewer's calendar, so a deadline
         * comes before an interview later that day. Ties go by [kind], then by subject, so the order is stable.
         */
        fun soonestFirst(viewer: ZoneId): Comparator<DashboardCountdown> =
            compareBy<DashboardCountdown> { it.target.endsAt(viewer) }
                .thenBy { it.kind }
                .thenBy { it.subject.type }
                .thenBy { it.subject.id }
    }
}
