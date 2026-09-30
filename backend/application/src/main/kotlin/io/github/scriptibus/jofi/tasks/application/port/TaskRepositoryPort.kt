// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application.port

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult

/**
 * Stores tasks (table `task`; implemented with the use cases in #93). The use case appends the changelog entry
 * (entity [TaskId.ENTITY_TYPE]; field names only, never the title or notes) in the same transaction.
 * Implementations never throw and never log row data. Violations are mapped **by constraint name** (ADR-0041):
 * `task_application_fk`, `task_company_fk` and `task_contact_fk` to [TaskStoreResult.LinkNotFound],
 * `task_suggestion_unique` to [TaskStoreResult.SuggestionExists]; anything else is a `StorageFailure`.
 */
interface TaskRepositoryPort {
    /** Stores a new task or suggestion. */
    fun add(task: Task): TaskStoreResult<Unit>

    /**
     * Stores all of [task] (details, state, completion time) only if the stored version is exactly one below
     * [task]'s, [TaskStoreResult.VersionConflict] otherwise.
     */
    fun update(task: Task): TaskStoreResult<Unit>

    fun findById(id: TaskId): TaskStoreResult<Task>

    /** The tasks in [state], oldest first (then by id); the use case groups and orders them for the viewer. */
    fun listByState(state: TaskState): TaskStoreResult<List<Task>>

    /** The tasks linked to [link] in any state, oldest first (then by id), for its timeline (#94). */
    fun listByLink(link: TaskLink): TaskStoreResult<List<Task>>

    /**
     * Deletes the task. [proof] is what the confirmation gate returned (ADR-0039): the adapter answers
     * [TaskStoreResult.NotConfirmed] unless `proof.covers(Task.DELETE_OPERATION, id.value.toString())`.
     */
    fun delete(
        id: TaskId,
        proof: ConfirmationResult.Confirmed,
    ): TaskStoreResult<Unit>
}
