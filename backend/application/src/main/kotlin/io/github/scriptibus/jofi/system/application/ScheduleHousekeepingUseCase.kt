// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.system.domain.SessionCleanup

/**
 * Registers the recurring housekeeping jobs when `app` starts; the worker runs them. Registering an
 * unchanged schedule again keeps it as it is, so restarts neither duplicate nor reset it.
 */
class ScheduleHousekeepingUseCase(
    private val jobs: JobSchedulerPort,
) {
    fun execute(): JobResult<Unit> =
        jobs.scheduleRecurring(SessionCleanup.RECURRING_ID, SessionCleanup.SCHEDULE, SessionCleanup.REQUEST)
}
