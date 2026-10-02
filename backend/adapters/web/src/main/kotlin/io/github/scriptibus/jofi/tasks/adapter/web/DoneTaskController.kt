// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.tasks.application.ListDoneTasksUseCase
import io.github.scriptibus.jofi.tasks.domain.DoneTaskPage
import io.github.scriptibus.jofi.tasks.domain.DoneTaskQuery
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * The done tasks (#235), apart from [TaskController] so neither takes more use cases than the constructor limit
 * allows. A done task is in no other list; reopening one is `POST /api/tasks/{id}/reopen`.
 */
@RestController
@RequestMapping("/api/tasks")
class DoneTaskController(
    private val listDoneTasks: ListDoneTasksUseCase,
) {
    /** One page of the done tasks, the newest completion first. An out-of-range `page` or `size` is a 400. */
    @GetMapping("/done")
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun listDoneTasks(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${DoneTaskQuery.DEFAULT_SIZE}") size: Int,
    ): DoneTaskPageResponse {
        val query = DoneTaskQuery.of(page, size) ?: throw TaskProblems.invalidDonePage(page, size)
        return DoneTaskPageResponse.from(listDoneTasks.execute(query).orThrow(), query)
    }

    /** `?page=abc` answers the same 400 shape as an out-of-range value, not the framework's. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun notANumber(mismatch: MethodArgumentTypeMismatchException): ErrorResponse =
        TaskProblems.notANumber(mismatch.name)
}

/** JSON body of `GET /api/tasks/done`: one page of done tasks, the newest completion first. */
data class DoneTaskPageResponse(
    val tasks: List<TaskResponse>,
    val page: Int,
    val size: Int,
    /** All done tasks, across pages. */
    val total: Long,
) {
    companion object {
        fun from(
            done: DoneTaskPage,
            query: DoneTaskQuery,
        ): DoneTaskPageResponse =
            DoneTaskPageResponse(done.tasks.map(TaskResponse::from), query.page, query.size, done.total)
    }
}
