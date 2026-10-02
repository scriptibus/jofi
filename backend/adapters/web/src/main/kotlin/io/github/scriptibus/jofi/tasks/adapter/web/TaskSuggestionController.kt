// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.tasks.application.AcceptTaskSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.DismissTaskSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.ListSuggestedTasksUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The suggested tasks (ADR-0049), apart from [TaskController] so neither takes more use cases than the constructor
 * limit allows: listing, accepting and dismissing, each calling its use case as `Actor.User`.
 */
@RestController
@RequestMapping("/api/tasks")
class TaskSuggestionController(
    private val listSuggestions: ListSuggestedTasksUseCase,
    private val acceptSuggestion: AcceptTaskSuggestionUseCase,
    private val dismissSuggestion: DismissTaskSuggestionUseCase,
) {
    /**
     * One page of the suggestions waiting to be accepted or dismissed, newest first (ADR-0056): `page` from 0 and
     * `size` of 1 to 50 (default 20), a 400 naming them when out of range; notes come as an excerpt.
     */
    @GetMapping("/suggestions")
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun listSuggestedTasks(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): TaskListResponse = TaskListResponse.from(listSuggestions.execute(PageInput(page, size)).orThrow())

    /**
     * Accepts a suggestion with one click: it becomes an open task. 409 `invalid-transition` for a done or dismissed
     * task.
     */
    @PostMapping("/{id}/accept")
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun acceptTaskSuggestion(
        @PathVariable id: UUID,
        @RequestBody request: TaskVersionRequest,
    ): TaskResponse =
        TaskResponse.from(acceptSuggestion.execute(TaskId(id), request.basedOnVersion, Actor.User).orThrow())

    /** Dismisses a suggestion; it is not suggested again. 409 `invalid-transition` for a task that is no suggestion. */
    @PostMapping("/{id}/dismiss")
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun dismissTaskSuggestion(
        @PathVariable id: UUID,
        @RequestBody request: TaskVersionRequest,
    ): TaskResponse =
        TaskResponse.from(dismissSuggestion.execute(TaskId(id), request.basedOnVersion, Actor.User).orThrow())
}
