// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownInput
import io.github.scriptibus.jofi.tasks.domain.CountdownTarget
import io.github.scriptibus.jofi.tasks.domain.DashboardCountdown
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// Countdown titles are the user's words (or an application's title): DTOs that hold them do not print them.

/** A custom countdown as entered; violations name `title` and `targetDate`. */
data class CountdownRequest(
    val title: String,
    val targetDate: LocalDate,
) {
    fun toInput(): CountdownInput = CountdownInput(title, targetDate)

    override fun toString(): String = "CountdownRequest(targetDate=$targetDate)"
}

/** Body of `PUT /api/countdowns/{id}`: title and date and the version they are based on. */
data class UpdateCountdownRequest(
    val details: CountdownRequest,
    val basedOnVersion: Long,
)

/** One custom countdown; [version] goes back as `basedOnVersion` with the next change. */
data class CountdownResponse(
    val id: UUID,
    val title: String,
    val targetDate: LocalDate,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "CountdownResponse(id=$id, targetDate=$targetDate, version=$version)"

    companion object {
        fun from(countdown: Countdown): CountdownResponse =
            CountdownResponse(
                countdown.id.value,
                countdown.details.title,
                countdown.details.targetDate,
                countdown.version,
                countdown.createdAt,
                countdown.updatedAt,
            )
    }
}

/** JSON body of `GET /api/countdowns`: the custom countdowns, soonest target first. */
data class CountdownListResponse(
    val countdowns: List<CountdownResponse>,
) {
    companion object {
        fun from(countdowns: List<Countdown>): CountdownListResponse =
            CountdownListResponse(countdowns.map(CountdownResponse::from))
    }
}

/**
 * One countdown on the dashboard: what it counts down to ([source]), a [title] to show, and either a [targetDate]
 * (count days on the viewer's calendar) or [targetAt] (an instant, shown as [localTarget] in [timeZone], the zone it
 * was planned in). [subjectType] and [subjectId] name what it belongs to (`countdown`, `application`, `interview`).
 */
data class DashboardCountdownResponse(
    val source: CountdownSource,
    val title: String,
    val targetDate: LocalDate?,
    val targetAt: Instant?,
    val localTarget: LocalDateTime?,
    val timeZone: String?,
    val subjectType: String,
    val subjectId: String,
) {
    override fun toString(): String =
        "DashboardCountdownResponse(source=$source, targetDate=$targetDate, targetAt=$targetAt, subject=$subjectType)"

    companion object {
        fun from(countdown: DashboardCountdown): DashboardCountdownResponse {
            val target = countdown.target
            val day = (target as? CountdownTarget.OnDay)?.date
            val at = target as? CountdownTarget.At
            return DashboardCountdownResponse(
                countdown.kind.toEnum(),
                countdown.title,
                day,
                at?.instant,
                at?.let { LocalDateTime.ofInstant(it.instant, it.zone) },
                at?.zone?.id,
                countdown.subject.type,
                countdown.subject.id,
            )
        }
    }
}

/** JSON body of `GET /api/dashboard/countdowns`: soonest first. */
data class DashboardCountdownListResponse(
    val countdowns: List<DashboardCountdownResponse>,
) {
    companion object {
        fun from(countdowns: List<DashboardCountdown>): DashboardCountdownListResponse =
            DashboardCountdownListResponse(countdowns.map(DashboardCountdownResponse::from))
    }
}
