// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application.port.inbound

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDashboard
import io.github.scriptibus.jofi.tasks.domain.TaskGroup
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskInput
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.ZoneId

// Inbound ports for tasks (#80, ADR-0049), implemented by the use cases of the same name (#93, #94, #95). An unknown
// task is `NotFound`; a link to something that does not exist is `Invalid` (LINK, NOT_FOUND, from the store's
// `LinkNotFound`). Mutations take the acting `Actor` and write a changelog entry (entity `task`) that names the changed
// fields, never the title or notes. `basedOnVersion` is the `Task.version` the caller last read: a stale one is
// `VersionConflict`, checked first, even for a no-op. Timestamps are `clock.instant().truncatedTo(ChronoUnit.MICROS)`,
// which is also the instant a bucket is resolved with (`TaskInput.validate`). State changes go through `Task.apply`
// with one `TaskTransition` per port, so no port does another's job (reopen never accepts a suggestion).

/** Creates an open task (#93): [origin] is `Manual` from the app, `Chat` from the built-in chat or an MCP client. */
interface CreateTaskPort {
    fun execute(
        input: TaskInput,
        origin: TaskOrigin.Direct,
        actor: Actor,
    ): TaskResult<Task>
}

/**
 * Replaces **all** details of the task with [input] (a PUT; a field left out is cleared) in any state (#93).
 * Unchanged details store nothing and write no changelog entry.
 */
interface UpdateTaskPort {
    fun execute(
        id: TaskId,
        input: TaskInput,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Task>
}

interface GetTaskPort {
    fun execute(id: TaskId): TaskResult<Task>
}

/**
 * Marks an open task done (#93, `TaskTransition.COMPLETE`); a done one is unchanged, any other state is
 * `InvalidTransition`.
 */
interface CompleteTaskPort {
    fun execute(
        id: TaskId,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Task>
}

/**
 * Opens a done task again (#93, `TaskTransition.REOPEN`); an open one is unchanged, a suggestion or dismissed one is
 * `InvalidTransition`.
 */
interface ReopenTaskPort {
    fun execute(
        id: TaskId,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Task>
}

/**
 * Deletes a task in two steps (ADR-0039, #93): without [token] it answers [TaskResult.Unconfirmed] with a token bound
 * to [Task.DELETE_OPERATION], the task id and the effect `ConfirmationEffect("task", <title>)`; with the token, it
 * deletes. The actor is [requester]'s.
 */
interface DeleteTaskPort {
    fun execute(
        id: TaskId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): TaskResult<Unit>
}

/**
 * The open tasks grouped by when they are due (#94), as seen on the calendar of [zone] (the viewer's), weeks from
 * Monday: every group of `TaskGroupKind` in its order, empty ones included. Reads only.
 */
interface ListTaskGroupsPort {
    fun execute(zone: ZoneId): TaskResult<List<TaskGroup>>
}

/**
 * The dashboard's open tasks (#113, ADR-0052) on the calendar of [zone] (the viewer's): the overdue ones and those due
 * within the next seven days, today included. Reads only.
 */
interface GetTaskDashboardPort {
    fun execute(zone: ZoneId): TaskResult<TaskDashboard>
}

/** The suggestions waiting for one click (#95), newest first. Reads only. */
interface ListSuggestedTasksPort {
    fun execute(): TaskResult<List<Task>>
}

/**
 * Accepts a suggestion (#95, `TaskTransition.ACCEPT`): it becomes an open task of the user. An accepted suggestion is
 * unchanged; a done or dismissed one, and a task that never was a suggestion, is `InvalidTransition` (#237).
 */
interface AcceptTaskSuggestionPort {
    fun execute(
        id: TaskId,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Task>
}

/**
 * Dismisses a suggestion (#95), by the user or, when it is obsolete, by its rule (`Actor.System`). It stays stored
 * as dismissed, so the rule does not suggest it again (`TaskTransition.DISMISS`). A dismissed one is unchanged, any
 * other state is `InvalidTransition`.
 */
interface DismissTaskSuggestionPort {
    fun execute(
        id: TaskId,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Task>
}
