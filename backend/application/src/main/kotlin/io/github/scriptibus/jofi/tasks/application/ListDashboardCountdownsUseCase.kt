// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.tasks.application.port.CountdownRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.ListDashboardCountdownsPort
import io.github.scriptibus.jofi.tasks.domain.CountdownKind
import io.github.scriptibus.jofi.tasks.domain.CountdownTarget
import io.github.scriptibus.jofi.tasks.domain.DashboardCountdown
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.Clock
import java.time.ZoneId

/**
 * The dashboard's countdowns (spec §10.1): every custom one (a past one shows as reached), and from the applications
 * context's API (named interface `api`) the next interview still to come, the application deadlines and the offer
 * answer deadlines from today on the viewer's calendar. Soonest first ([DashboardCountdown.soonestFirst]). Reads only.
 */
class ListDashboardCountdownsUseCase(
    private val countdowns: CountdownRepositoryPort,
    private val facts: FindCountdownFactsPort,
    private val clock: Clock,
) : ListDashboardCountdownsPort {
    override fun execute(zone: ZoneId): TaskResult<List<DashboardCountdown>> =
        countdowns.list().toCountdownResult().then { custom ->
            val now = clock.instant()
            when (val found = facts.execute(now, now.atZone(zone).toLocalDate())) {
                is FindCountdownFactsPort.Facts.Found -> {
                    val all = custom.map(DashboardCountdown::of) + derived(found)
                    TaskResult.Success(all.sortedWith(DashboardCountdown.soonestFirst(zone)))
                }

                FindCountdownFactsPort.Facts.Unavailable -> {
                    TaskResult.StorageFailure("countdown facts")
                }
            }
        }

    private fun derived(found: FindCountdownFactsPort.Facts.Found): List<DashboardCountdown> {
        val interview =
            found.nextInterview?.let {
                DashboardCountdown(
                    CountdownKind.NEXT_INTERVIEW,
                    it.title,
                    CountdownTarget.At(it.startsAt, it.zone),
                    EntityRef(FindCountdownFactsPort.INTERVIEW_ENTITY_TYPE, it.interview.toString()),
                    it.application,
                )
            }
        return listOfNotNull(interview) +
            found.deadlines.map { onDay(CountdownKind.APPLICATION_DEADLINE, it) } +
            found.offerAnswers.map { onDay(CountdownKind.OFFER_ANSWER_DEADLINE, it) }
    }

    private fun onDay(
        kind: CountdownKind,
        due: FindCountdownFactsPort.DueDate,
    ): DashboardCountdown =
        DashboardCountdown(
            kind,
            due.title,
            CountdownTarget.OnDay(due.date),
            EntityRef(FindCountdownFactsPort.APPLICATION_ENTITY_TYPE, due.application.toString()),
        )
}
