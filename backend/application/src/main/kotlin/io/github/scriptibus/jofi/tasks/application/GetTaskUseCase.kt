// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.GetTaskPort
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskResult

/** One task, in any state. */
class GetTaskUseCase(
    private val tasks: TaskRepositoryPort,
) : GetTaskPort {
    override fun execute(id: TaskId): TaskResult<Task> = tasks.findById(id).toResult()
}
