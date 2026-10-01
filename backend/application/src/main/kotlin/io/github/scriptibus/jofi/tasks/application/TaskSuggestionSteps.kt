// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.SuggestionRun
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import java.time.Instant
import java.util.UUID

// How the suggestion runs (#85, #95) keep the suggestions of their rules in line with the facts (ADR-0049).

/**
 * Brings the suggestions of [rules] in line with [wanted] as of [now]. Each wanted suggestion is added unless its rule
 * suggested it before: `task_suggestion_unique` answers that, so a repeated or retried run adds nothing and a
 * dismissed suggestion is not made again. Each waiting suggestion of [rules] that is no longer wanted is obsolete and
 * dismissed. Both are recorded as the rule (`Actor.System(<rule>)`), each in its own transaction with its changelog
 * entry, so one failure keeps what was done before; the first failure is the result.
 */
internal fun TaskRepositoryPort.reconcileSuggestions(
    wanted: Map<TaskOrigin.Suggested, TaskDetails>,
    rules: Set<String>,
    now: Instant,
    changelog: ChangelogPort,
    transactions: TransactionPort,
): TaskResult<SuggestionRun> {
    val suggested = wanted.map { (origin, details) -> suggest(origin, details, now, changelog, transactions) }
    return waiting(rules).then { waiting ->
        val dismissed = waiting.filter { it.origin !in wanted.keys }.map { dismiss(it, now, changelog, transactions) }
        (suggested + dismissed).filterIsInstance<TaskResult.Failure>().firstOrNull()
            ?: TaskResult.Success(SuggestionRun(suggested.count(::changed), dismissed.count(::changed)))
    }
}

/** Adds the suggestion; true if it is new, false if its rule suggested it before. */
private fun TaskRepositoryPort.suggest(
    origin: TaskOrigin.Suggested,
    details: TaskDetails,
    now: Instant,
    changelog: ChangelogPort,
    transactions: TransactionPort,
): TaskResult<Boolean> {
    val task = Task.suggest(TaskId(UUID.randomUUID()), details, origin, now)
    return transactions.inTransaction({ it == TaskResult.Success(true) }) {
        when (val added = add(task)) {
            is TaskStoreResult.Success -> {
                val fields = detailChanges(null, task.details)
                true.taskIf(changelog.record(task, Actor.System(origin.rule), "Suggested task", fields), "changelog")
            }

            // Rolled back: the failed insert ended the transaction, and nothing else was written in it.
            TaskStoreResult.SuggestionExists -> {
                TaskResult.Success(false)
            }

            else -> {
                added.toResult().then { TaskResult.Success(false) }
            }
        }
    }
}

/** The waiting suggestions of [rules]. */
private fun TaskRepositoryPort.waiting(rules: Set<String>): TaskResult<List<Task>> =
    listByState(TaskState.SUGGESTED).toResult().then { suggestions ->
        TaskResult.Success(suggestions.filter { (it.origin as? TaskOrigin.Suggested)?.rule in rules })
    }

/** Dismisses an obsolete suggestion as its rule; true if it was dismissed now. */
private fun TaskRepositoryPort.dismiss(
    task: Task,
    now: Instant,
    changelog: ChangelogPort,
    transactions: TransactionPort,
): TaskResult<Boolean> {
    val rule = (task.origin as TaskOrigin.Suggested).rule
    return transactions.inTaskTransaction {
        when (val change = task.apply(TaskTransition.DISMISS, now)) {
            is TaskStateChange.Changed -> {
                update(change.task)
                    .toResult()
                    .then {
                        changelog.recordMove(task, change.task, Actor.System(rule), "Dismissed obsolete suggestion")
                    }.then { TaskResult.Success(true) }
            }

            else -> {
                TaskResult.Success(false)
            }
        }
    }
}

private fun changed(outcome: TaskResult<Boolean>): Boolean = outcome == TaskResult.Success(true)
