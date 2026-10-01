// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort.DueDate
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import java.time.LocalDate

/**
 * Reads the days applications count down to, for the dashboard countdowns (#112). Each list is soonest first (then by
 * id) and holds at most `limit` entries. Implementations never throw and never log row data.
 */
interface ApplicationDueDatesRepositoryPort {
    /** The deadlines on or after [from] of the applications in one of [statuses]. */
    fun deadlines(
        from: LocalDate,
        statuses: Set<ApplicationStatus>,
        limit: Int,
    ): ApplicationStoreResult<List<DueDate>>

    /** The offer answer dates on or after [from] of the applications at `OFFER`. */
    fun offerAnswers(
        from: LocalDate,
        limit: Int,
    ): ApplicationStoreResult<List<DueDate>>
}
