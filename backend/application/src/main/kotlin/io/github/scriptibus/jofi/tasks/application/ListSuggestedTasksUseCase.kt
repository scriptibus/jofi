// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.ListSuggestedTasksPort
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskSummary

/** One page of the suggestions waiting for the user (ADR-0049, ADR-0056), newest first, notes as excerpts. */
class ListSuggestedTasksUseCase(
    private val tasks: TaskRepositoryPort,
) : ListSuggestedTasksPort {
    override fun execute(page: PageInput): TaskResult<Paged<TaskSummary>> =
        page.toResult().then { request ->
            tasks.pageByStateNewestFirst(TaskState.SUGGESTED, request).toResult().then { stored ->
                TaskResult.Success(Paged(stored.items.map(TaskSummary::of), stored.info))
            }
        }
}
