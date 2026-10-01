// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import java.time.Instant

/**
 * Reads when applications last had activity, for the Ghosted suggestion (#85) and the follow-up (#95). What counts as
 * activity is defined on [FindGhostedCandidatesPort]. Implementations never throw and never log row data.
 */
interface ApplicationActivityRepositoryPort {
    /**
     * The applications in one of [statuses] whose last activity is at or before [cutoff], longest silent first (then
     * by id).
     */
    fun silentSince(
        cutoff: Instant,
        statuses: Set<ApplicationStatus>,
    ): ApplicationStoreResult<List<FindGhostedCandidatesPort.Candidate>>
}
