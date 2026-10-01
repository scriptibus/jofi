// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.GetTaskDashboardPort
import io.github.scriptibus.jofi.tasks.domain.TaskCalendar
import io.github.scriptibus.jofi.tasks.domain.TaskDashboard
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import java.time.Clock
import java.time.ZoneId

/**
 * The dashboard's overdue and upcoming tasks ([TaskCalendar.dashboard], ADR-0052): the open tasks, read in one query,
 * sorted at the clock's "now" in the viewer's zone, as the task list groups them (ADR-0049).
 */
class GetTaskDashboardUseCase(
    private val tasks: TaskRepositoryPort,
    private val clock: Clock,
) : GetTaskDashboardPort {
    override fun execute(zone: ZoneId): TaskResult<TaskDashboard> {
        val calendar = TaskCalendar(clock.storedNow(), zone)
        return tasks.listByState(TaskState.OPEN).toResult().then { TaskResult.Success(calendar.dashboard(it)) }
    }
}
