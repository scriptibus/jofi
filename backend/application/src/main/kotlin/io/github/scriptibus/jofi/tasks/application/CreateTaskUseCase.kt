// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.CreateTaskPort
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskInput
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.Clock
import java.util.UUID

/**
 * Creates an open task (spec §10.2). A bucket is resolved on today's date in the zone the input names, taken from the
 * instant the task is created at (ADR-0049). The task and its changelog entry are stored together; a link to something
 * that does not exist is `Invalid` (LINK, NOT_FOUND), found by the link's foreign key.
 */
class CreateTaskUseCase(
    private val tasks: TaskRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : CreateTaskPort {
    override fun execute(
        input: TaskInput,
        origin: TaskOrigin.Direct,
        actor: Actor,
    ): TaskResult<Task> {
        val now = clock.storedNow()
        return input.validate(now).toResult().then { details ->
            val task = Task.create(TaskId(UUID.randomUUID()), details, origin, now)
            transactions.inTaskTransaction {
                tasks.add(task).toResult().then {
                    val recorded = changelog.record(task, actor, "Created task", detailChanges(null, details))
                    task.taskIf(recorded, "changelog")
                }
            }
        }
    }
}
