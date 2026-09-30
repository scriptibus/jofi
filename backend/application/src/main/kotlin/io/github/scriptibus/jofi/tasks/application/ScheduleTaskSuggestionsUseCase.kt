// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.tasks.domain.TaskSuggestionRules

/**
 * Registers the daily run of the suggestion rules ([TaskSuggestionRules]) when `app` starts; the worker runs it.
 * Registering the unchanged schedule again keeps it as it is (ADR-0038).
 */
class ScheduleTaskSuggestionsUseCase(
    private val jobs: JobSchedulerPort,
) {
    fun execute(): JobResult<Unit> =
        jobs.scheduleRecurring(
            TaskSuggestionRules.RECURRING_ID,
            TaskSuggestionRules.SCHEDULE,
            TaskSuggestionRules.REQUEST,
        )
}
