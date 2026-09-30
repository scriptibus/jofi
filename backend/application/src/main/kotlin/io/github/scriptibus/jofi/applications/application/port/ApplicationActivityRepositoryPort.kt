// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import java.time.Instant

/**
 * Reads when applications last had activity, for the Ghosted suggestion (#85). What counts as activity is defined on
 * [FindGhostedCandidatesPort]. Implementations never throw and never log row data.
 */
interface ApplicationActivityRepositoryPort {
    /**
     * The `APPLIED` and `INTERVIEWING` applications whose last activity is at or before [cutoff], longest silent
     * first (then by id).
     */
    fun silentSince(cutoff: Instant): ApplicationStoreResult<List<FindGhostedCandidatesPort.Candidate>>
}
