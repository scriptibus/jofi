// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.ListSuggestedTasksPort
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState

/** The suggestions waiting for the user (ADR-0049), newest first. Reads only. */
class ListSuggestedTasksUseCase(
    private val tasks: TaskRepositoryPort,
) : ListSuggestedTasksPort {
    override fun execute(): TaskResult<List<Task>> =
        tasks.listByState(TaskState.SUGGESTED).toResult().then { oldestFirst ->
            TaskResult.Success(oldestFirst.asReversed())
        }
}
