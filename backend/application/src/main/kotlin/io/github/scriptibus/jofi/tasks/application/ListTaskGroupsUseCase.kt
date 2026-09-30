// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.ListTaskGroupsPort
import io.github.scriptibus.jofi.tasks.domain.TaskCalendar
import io.github.scriptibus.jofi.tasks.domain.TaskGroup
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import java.time.Clock
import java.time.ZoneId

/**
 * The open tasks grouped on the viewer's calendar ([TaskCalendar], ADR-0049): one query for all of them, grouped at
 * the clock's "now" in the viewer's zone. Done tasks and suggestions (pending or dismissed) are not in the list.
 */
class ListTaskGroupsUseCase(
    private val tasks: TaskRepositoryPort,
    private val clock: Clock,
) : ListTaskGroupsPort {
    override fun execute(zone: ZoneId): TaskResult<List<TaskGroup>> {
        val calendar = TaskCalendar(clock.storedNow(), zone)
        return tasks.listByState(TaskState.OPEN).toResult().then { TaskResult.Success(calendar.group(it)) }
    }
}
