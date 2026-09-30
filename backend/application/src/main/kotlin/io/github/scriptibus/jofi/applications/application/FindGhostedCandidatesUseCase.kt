// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationActivityRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationSettingsRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import java.time.Duration
import java.time.Instant

/**
 * The applications silent for the Ghosted period of the settings (ADR-0050), for the tasks context's suggestion
 * (#85). A week is seven days of 24 hours: the period needs no time zone. Reads only; changes nothing.
 */
class FindGhostedCandidatesUseCase(
    private val settings: ApplicationSettingsRepositoryPort,
    private val activity: ApplicationActivityRepositoryPort,
) : FindGhostedCandidatesPort {
    override fun execute(at: Instant): FindGhostedCandidatesPort.Candidates {
        val current = settings.find() as? ApplicationStoreResult.Success ?: return unavailable
        val cutoff = at.minus(Duration.ofDays(DAYS_PER_WEEK * current.value.values.ghostedAfterWeeks))
        return when (val silent = activity.silentSince(cutoff, GHOSTED_STATUSES)) {
            is ApplicationStoreResult.Success -> FindGhostedCandidatesPort.Candidates.Found(silent.value)
            else -> unavailable
        }
    }

    private companion object {
        const val DAYS_PER_WEEK = 7L

        /** Waiting for an answer after applying (spec §6.2). */
        val GHOSTED_STATUSES = setOf(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEWING)
        val unavailable = FindGhostedCandidatesPort.Candidates.Unavailable
    }
}
