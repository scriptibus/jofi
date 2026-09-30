// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.CompleteTaskUseCase
import io.github.scriptibus.jofi.tasks.application.CreateTaskUseCase
import io.github.scriptibus.jofi.tasks.application.DeleteTaskUseCase
import io.github.scriptibus.jofi.tasks.application.GetTaskUseCase
import io.github.scriptibus.jofi.tasks.application.ListTaskGroupsUseCase
import io.github.scriptibus.jofi.tasks.application.ReopenTaskUseCase
import io.github.scriptibus.jofi.tasks.application.UpdateTaskUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Tasks with exact or rough timing (spec §10.2, ADR-0049), for the logged-in user. Create, read, edit, complete,
 * reopen and delete (#93) call their use case as `Actor.User` (tasks created here are `Manual`) and map each
 * `TaskResult.Failure` with [TaskProblems.of]; so does the grouped list (#94). The suggestions have their own
 * [TaskSuggestionController].
 */
@RestController
@RequestMapping("/api/tasks")
class TaskController(
    private val createTask: CreateTaskUseCase,
    private val getTask: GetTaskUseCase,
    private val listTaskGroups: ListTaskGroupsUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val completeTask: CompleteTaskUseCase,
    private val reopenTask: ReopenTaskUseCase,
    private val deleteTask: DeleteTaskUseCase,
) {
    /**
     * The open tasks grouped by when they are due, as seen on the calendar of [timeZone] (the viewer's zone, e.g.
     * `Europe/Berlin` or `+02:00`; weeks start on Monday). An unknown zone is a 400 naming `timeZone`.
     */
    @GetMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun listTaskGroups(
        @RequestParam timeZone: String,
    ): TaskGroupListResponse {
        val zone = TaskTiming.zoneOf(timeZone) ?: throw TaskProblems.invalidViewerZone()
        return TaskGroupListResponse.from(listTaskGroups.execute(zone).orThrow())
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createTask(
        @RequestBody request: TaskRequest,
    ): TaskResponse = TaskResponse.from(createTask.execute(request.toInput(), TaskOrigin.Manual, Actor.User).orThrow())

    @GetMapping("/{id}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getTask(
        @PathVariable id: UUID,
    ): TaskResponse = TaskResponse.from(getTask.execute(TaskId(id)).orThrow())

    /** Replaces all details (anything left out is cleared); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateTask(
        @PathVariable id: UUID,
        @RequestBody request: UpdateTaskRequest,
    ): TaskResponse =
        TaskResponse.from(
            updateTask.execute(TaskId(id), request.details.toInput(), request.basedOnVersion, Actor.User).orThrow(),
        )

    /** Marks an open task done; 409 `invalid-transition` for a task in another state. */
    @PostMapping("/{id}/complete")
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun completeTask(
        @PathVariable id: UUID,
        @RequestBody request: TaskVersionRequest,
    ): TaskResponse = TaskResponse.from(completeTask.execute(TaskId(id), request.basedOnVersion, Actor.User).orThrow())

    /** Opens a done task again; 409 `invalid-transition` for a task in another state. */
    @PostMapping("/{id}/reopen")
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun reopenTask(
        @PathVariable id: UUID,
        @RequestBody request: TaskVersionRequest,
    ): TaskResponse = TaskResponse.from(reopenTask.execute(TaskId(id), request.basedOnVersion, Actor.User).orThrow())

    /** Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteTask(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ) {
        deleteTask.execute(TaskId(id), Confirmations.requester(request), Confirmations.token(confirmation)).orThrow()
    }
}

/** The value, or the failure's problem thrown for Spring to answer. */
internal fun <T> TaskResult<T>.orThrow(): T =
    when (this) {
        is TaskResult.Success -> value
        is TaskResult.Failure -> throw TaskProblems.of(this)
    }
