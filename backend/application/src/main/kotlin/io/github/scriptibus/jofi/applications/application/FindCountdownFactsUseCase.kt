// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationDueDatesRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort
import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort.Facts
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import java.time.Instant
import java.time.LocalDate

/**
 * The facts of the dashboard countdowns (#112): the next interview still to come (the instant decides, ADR-0048), the
 * deadlines of applications not yet applied for and the offer answer dates, from the caller's today on. Reads only;
 * changes nothing.
 */
class FindCountdownFactsUseCase(
    private val interviews: InterviewRepositoryPort,
    private val dueDates: ApplicationDueDatesRepositoryPort,
) : FindCountdownFactsPort {
    override fun execute(
        at: Instant,
        today: LocalDate,
    ): Facts {
        val limit = FindCountdownFactsPort.MAX_PER_KIND
        val next = interviews.upcoming(at, 1) as? ApplicationStoreResult.Success
        val deadlines = dueDates.deadlines(today, DEADLINE_STATUSES, limit) as? ApplicationStoreResult.Success
        val offers = dueDates.offerAnswers(today, limit) as? ApplicationStoreResult.Success
        if (next == null || deadlines == null || offers == null) return Facts.Unavailable
        val interview =
            next.value.firstOrNull()?.let {
                val time = it.interview.details.time
                FindCountdownFactsPort.NextInterview(
                    it.interview.id.value,
                    it.interview.application.value,
                    it.applicationTitle,
                    time.startsAt,
                    time.zone,
                )
            }
        return Facts.Found(interview, deadlines.value, offers.value)
    }

    private companion object {
        /** In the pipeline and not applied yet: the posting's deadline still matters. */
        val DEADLINE_STATUSES = ApplicationStatus.entries.filter { !it.isTerminal && !it.impliesApplied }.toSet()
    }
}
