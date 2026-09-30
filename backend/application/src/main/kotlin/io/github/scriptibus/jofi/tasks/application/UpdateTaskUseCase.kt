// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.UpdateTaskPort
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskInput
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.Clock
import java.time.Instant

/**
 * Replaces all details of a task, in any state. The version is checked first; unchanged details store nothing and
 * write no changelog entry. A bucket is resolved again on today's date, so "this week" sent a week later is the new
 * week. Read and write share one transaction, and the repository's version check catches a change in between.
 */
class UpdateTaskUseCase(
    private val tasks: TaskRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : UpdateTaskPort {
    override fun execute(
        id: TaskId,
        input: TaskInput,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Task> {
        val now = clock.storedNow()
        return transactions.inTaskTransaction {
            tasks
                .findById(id)
                .toResult()
                .then { it.basedOn(basedOnVersion) }
                .then { current -> input.validate(now).toResult().then { edit(current, it, actor, now) } }
        }
    }

    private fun edit(
        current: Task,
        details: TaskDetails,
        actor: Actor,
        now: Instant,
    ): TaskResult<Task> {
        val edited = current.edit(details, now)
        if (edited == current) return TaskResult.Success(current)
        return tasks.update(edited).toResult().then {
            val description = describe("Edited task", current.details, details)
            val recorded = changelog.record(edited, actor, description, detailChanges(current.details, details))
            edited.taskIf(recorded, "changelog")
        }
    }
}
