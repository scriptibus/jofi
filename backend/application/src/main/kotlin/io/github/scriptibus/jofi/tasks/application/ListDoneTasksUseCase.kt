// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.ListDoneTasksPort
import io.github.scriptibus.jofi.tasks.domain.DoneTaskPage
import io.github.scriptibus.jofi.tasks.domain.DoneTaskQuery
import io.github.scriptibus.jofi.tasks.domain.TaskResult

/**
 * One page of the done tasks, newest completion first (#235), so a completed task can be found and reopened. The
 * open list ([ListTaskGroupsUseCase]) stays as it is. Reads only.
 */
class ListDoneTasksUseCase(
    private val tasks: TaskRepositoryPort,
) : ListDoneTasksPort {
    override fun execute(query: DoneTaskQuery): TaskResult<DoneTaskPage> = tasks.listDone(query).toResult()
}
