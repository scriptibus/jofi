// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.tasks.domain.GhostedSuggestion

/**
 * Registers the daily Ghosted suggestion when `app` starts; the worker runs it. Registering the unchanged schedule
 * again keeps it as it is, so restarts neither duplicate nor reset it (ADR-0038).
 */
class ScheduleGhostedSuggestionUseCase(
    private val jobs: JobSchedulerPort,
) {
    fun execute(): JobResult<Unit> =
        jobs.scheduleRecurring(GhostedSuggestion.RECURRING_ID, GhostedSuggestion.SCHEDULE, GhostedSuggestion.REQUEST)
}
