// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.DeleteTaskPort
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.Clock

/**
 * Deletes a task in two steps (ADR-0039), in any state. The task is read in the transaction of the delete and the
 * confirmation effect (its title) is built from that read, so renaming it between the steps voids the token. Nothing
 * goes with a task. The changelog entry keeps only the id: the title is not repeated anywhere.
 */
class DeleteTaskUseCase(
    private val tasks: TaskRepositoryPort,
    private val confirmation: ConfirmActionUseCase,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : DeleteTaskPort {
    override fun execute(
        id: TaskId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): TaskResult<Unit> =
        transactions.inTaskTransaction {
            tasks.findById(id).toResult().then { confirmThenDelete(it, requester, token) }
        }

    private fun confirmThenDelete(
        task: Task,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): TaskResult<Unit> {
        val effect = ConfirmationEffect(TaskId.ENTITY_TYPE, task.details.title)
        val action = ConfirmableAction(Task.DELETE_OPERATION, listOf(task.id.value.toString()), effect)
        return when (val outcome = confirmation.execute(ConfirmationRequest(requester, action, token))) {
            is ConfirmationResult.Confirmed -> {
                tasks.delete(task.id, outcome).toResult().then { record(task.id, requester.actor) }
            }

            is ConfirmationResult.Unconfirmed -> {
                TaskResult.Unconfirmed(outcome)
            }
        }
    }

    private fun record(
        id: TaskId,
        actor: Actor,
    ): TaskResult<Unit> {
        val entry = ChangelogEntry(id.toEntityRef(), actor, clock.storedNow(), ChangeSummary("Deleted task"))
        return Unit.taskIf(changelog.append(entry) is ChangelogResult.Success, "changelog")
    }
}
