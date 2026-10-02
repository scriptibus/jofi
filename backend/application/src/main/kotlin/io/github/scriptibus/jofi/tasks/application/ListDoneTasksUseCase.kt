// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.ListDoneTasksPort
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskResult

/**
 * One page of the done tasks, newest completion first (#235, ADR-0056), so a completed task can be found and
 * reopened. Page and size are limited like every list (`PageInput`). The open list ([ListTaskGroupsUseCase]) stays as
 * it is. Reads only.
 */
class ListDoneTasksUseCase(
    private val tasks: TaskRepositoryPort,
) : ListDoneTasksPort {
    override fun execute(page: PageInput): TaskResult<Paged<Task>> =
        page.toResult().then { request -> tasks.listDone(request).toResult() }
}
