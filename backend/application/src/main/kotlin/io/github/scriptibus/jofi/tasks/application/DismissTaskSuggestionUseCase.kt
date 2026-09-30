// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.DismissTaskSuggestionPort
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import java.time.Clock

/**
 * Dismisses a suggestion (ADR-0049, `TaskTransition.DISMISS`): it stays stored as dismissed, so its rule never makes
 * it again. A dismissed one is unchanged (no entry), any other state is `InvalidTransition`. The version is checked
 * first; the move and its changelog entry (field `state`) are stored together.
 */
class DismissTaskSuggestionUseCase(
    private val tasks: TaskRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : DismissTaskSuggestionPort {
    override fun execute(
        id: TaskId,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Task> =
        transactions.inTaskTransaction {
            tasks.move(id, basedOnVersion, TaskTransition.DISMISS, clock.storedNow()).then { (before, after) ->
                changelog.recordMove(before, after, actor, "Dismissed suggestion")
            }
        }
}
