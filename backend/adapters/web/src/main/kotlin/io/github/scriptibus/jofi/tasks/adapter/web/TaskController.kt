// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
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
 * Tasks with exact or rough timing (spec §10.2, ADR-0049). The contract only (#80): every operation answers
 * `501 Not Implemented` until #93 (create, read, edit, complete, reopen, delete), #94 (the grouped list) and #95
 * (suggestions) inject their use cases and map each `TaskResult.Failure` with [TaskProblems.of].
 */
@Suppress("UnusedParameter", "TooManyFunctions")
@RestController
@RequestMapping("/api/tasks")
class TaskController {
    /**
     * The open tasks grouped by when they are due, as seen on the calendar of [timeZone] (the viewer's zone, e.g.
     * `Europe/Berlin`; weeks start on Monday).
     */
    @GetMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun listTaskGroups(
        @RequestParam timeZone: String,
    ): TaskGroupListResponse = throw notImplemented()

    /** The suggestions waiting to be accepted or dismissed, newest first. */
    @GetMapping("/suggestions")
    fun listSuggestedTasks(): TaskListResponse = throw notImplemented()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createTask(
        @RequestBody request: TaskRequest,
    ): TaskResponse = throw notImplemented()

    @GetMapping("/{id}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getTask(
        @PathVariable id: UUID,
    ): TaskResponse = throw notImplemented()

    /** Replaces all details (anything left out is cleared); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateTask(
        @PathVariable id: UUID,
        @RequestBody request: UpdateTaskRequest,
    ): TaskResponse = throw notImplemented()

    /** Marks an open task done; 409 `invalid-transition` for a task in another state. */
    @PostMapping("/{id}/complete")
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun completeTask(
        @PathVariable id: UUID,
        @RequestBody request: TaskVersionRequest,
    ): TaskResponse = throw notImplemented()

    /** Opens a done task again; 409 `invalid-transition` for a task in another state. */
    @PostMapping("/{id}/reopen")
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun reopenTask(
        @PathVariable id: UUID,
        @RequestBody request: TaskVersionRequest,
    ): TaskResponse = throw notImplemented()

    /** Accepts a suggestion with one click: it becomes an open task. */
    @PostMapping("/{id}/accept")
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun acceptTaskSuggestion(
        @PathVariable id: UUID,
        @RequestBody request: TaskVersionRequest,
    ): TaskResponse = throw notImplemented()

    /** Dismisses a suggestion; it is not suggested again. */
    @PostMapping("/{id}/dismiss")
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun dismissTaskSuggestion(
        @PathVariable id: UUID,
        @RequestBody request: TaskVersionRequest,
    ): TaskResponse = throw notImplemented()

    /** Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteTask(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ): Unit = throw notImplemented()

    private fun notImplemented(): ErrorResponseException {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Tasks are not available yet")
        return ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null)
    }
}
