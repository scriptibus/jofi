// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.tasks.application.ListDoneTasksUseCase
import io.github.scriptibus.jofi.tasks.domain.Task
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The done tasks (#235), apart from [TaskController] so neither takes more use cases than the constructor limit
 * allows. A done task is in no other list; reopening one is `POST /api/tasks/{id}/reopen`.
 */
@RestController
@RequestMapping("/api/tasks")
class DoneTaskController(
    private val listDoneTasks: ListDoneTasksUseCase,
) {
    /**
     * One page of the done tasks, the newest completion first (ADR-0056): `page` from 0 and `size` of 1 to 50 (default
     * 20), a 400 naming them when out of range or no number.
     */
    @GetMapping("/done")
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun listDoneTasks(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): DoneTaskPageResponse = DoneTaskPageResponse.from(listDoneTasks.execute(PageInput(page, size)).orThrow())
}

/** JSON body of `GET /api/tasks/done`: one page of done tasks, the newest completion first. */
data class DoneTaskPageResponse(
    val tasks: List<TaskResponse>,
    val page: PageResponse,
) {
    companion object {
        fun from(done: Paged<Task>): DoneTaskPageResponse =
            DoneTaskPageResponse(done.items.map(TaskResponse::from), PageResponse.from(done.info))
    }
}
