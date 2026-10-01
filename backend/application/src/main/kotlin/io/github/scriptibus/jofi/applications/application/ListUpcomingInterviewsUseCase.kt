// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ListUpcomingInterviewsPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import java.time.Clock

/**
 * The interviews and calls still to come across all applications (spec §6.1): those starting at or after the
 * current instant that are not cancelled, soonest first, at most [Interview.MAX_UPCOMING]. The start is stored as an
 * instant (ADR-0048), so "still to come" needs no zone. Reads only.
 */
class ListUpcomingInterviewsUseCase(
    private val interviews: InterviewRepositoryPort,
    private val clock: Clock,
) : ListUpcomingInterviewsPort {
    override fun execute(): ApplicationResult<List<UpcomingInterview>> =
        interviews.upcoming(clock.storedNow(), Interview.MAX_UPCOMING).toResult()
}
