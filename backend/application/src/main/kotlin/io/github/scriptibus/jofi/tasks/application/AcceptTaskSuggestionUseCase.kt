// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.AcceptTaskSuggestionPort
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import java.time.Clock

/**
 * Accepts a suggestion with one click (ADR-0049, `TaskTransition.ACCEPT`): it becomes an open task, keeping its
 * origin, so its rule never suggests it again. An open one is unchanged (no entry); a done or dismissed one is
 * `InvalidTransition`. The version is checked first; the move and its changelog entry (field `state`) are stored
 * together.
 */
class AcceptTaskSuggestionUseCase(
    private val tasks: TaskRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : AcceptTaskSuggestionPort {
    override fun execute(
        id: TaskId,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Task> =
        transactions.inTaskTransaction {
            tasks.move(id, basedOnVersion, TaskTransition.ACCEPT, clock.storedNow()).then { (before, after) ->
                changelog.recordMove(before, after, actor, "Accepted suggestion")
            }
        }
}
