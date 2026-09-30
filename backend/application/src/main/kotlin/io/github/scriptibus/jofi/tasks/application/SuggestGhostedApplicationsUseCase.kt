// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.GhostedSuggestion
import io.github.scriptibus.jofi.tasks.domain.GhostedSuggestionRun
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStateChange
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTransition
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The daily Ghosted suggestion run (spec §6.2, [GhostedSuggestion]). It asks the applications context for the silent
 * applications (its named interface `api`; the tasks context depends on applications, never the reverse) and
 * suggests a task for each silence not suggested yet; `task_suggestion_unique` makes that idempotent, so a repeated
 * or retried run adds nothing, and a dismissed suggestion is not made again. A waiting suggestion whose silence ended
 * (an answer came, the status moved on, the user marked it Ghosted) is obsolete and dismissed. Each suggestion or
 * dismissal is stored with its changelog entry (actor [GhostedSuggestion.ACTOR]) in its own transaction, so one
 * failure keeps what was done before. It never changes an application.
 */
class SuggestGhostedApplicationsUseCase(
    private val candidates: FindGhostedCandidatesPort,
    private val tasks: TaskRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) {
    fun execute(): TaskResult<GhostedSuggestionRun> {
        val now = clock.storedNow()
        val silent =
            candidates.execute(now) as? FindGhostedCandidatesPort.Candidates.Found
                ?: return TaskResult.StorageFailure("find ghosted candidates")
        val origins = silent.candidates.associateBy { GhostedSuggestion.origin(it.id, it.silentSince) }
        val suggested = origins.entries.map { (origin, candidate) -> suggest(origin, candidate, now) }
        return waiting().then { waiting ->
            val obsolete = waiting.filter { it.origin !in origins.keys }
            val dismissed = obsolete.map { dismiss(it, now) }
            (suggested + dismissed).filterIsInstance<TaskResult.Failure>().firstOrNull()
                ?: TaskResult.Success(GhostedSuggestionRun(suggested.count(::changed), dismissed.count(::changed)))
        }
    }

    /** Adds the suggestion; true if it is new, false if this silence was suggested before. */
    private fun suggest(
        origin: TaskOrigin.Suggested,
        candidate: FindGhostedCandidatesPort.Candidate,
        now: Instant,
    ): TaskResult<Boolean> {
        val details = GhostedSuggestion.details(candidate.id, candidate.title)
        val task = Task.suggest(TaskId(UUID.randomUUID()), details, origin, now)
        return transactions.inTransaction({ it == TaskResult.Success(true) }) {
            when (val added = tasks.add(task)) {
                is TaskStoreResult.Success -> {
                    val fields = detailChanges(null, task.details)
                    true.taskIf(changelog.record(task, GhostedSuggestion.ACTOR, "Suggested task", fields), "changelog")
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

    /** The waiting suggestions of this rule. */
    private fun waiting(): TaskResult<List<Task>> =
        tasks.listByState(TaskState.SUGGESTED).toResult().then { suggestions ->
            TaskResult.Success(suggestions.filter { (it.origin as? TaskOrigin.Suggested)?.rule == RULE })
        }

    /** Dismisses an obsolete suggestion; true if it was dismissed now. */
    private fun dismiss(
        task: Task,
        now: Instant,
    ): TaskResult<Boolean> =
        transactions.inTaskTransaction {
            when (val change = task.apply(TaskTransition.DISMISS, now)) {
                is TaskStateChange.Changed -> {
                    tasks
                        .update(change.task)
                        .toResult()
                        .then {
                            changelog.recordMove(
                                task,
                                change.task,
                                GhostedSuggestion.ACTOR,
                                "Dismissed obsolete suggestion",
                            )
                        }.then { TaskResult.Success(true) }
                }

                else -> {
                    TaskResult.Success(false)
                }
            }
        }

    private fun changed(outcome: TaskResult<Boolean>): Boolean = outcome == TaskResult.Success(true)

    private companion object {
        const val RULE = GhostedSuggestion.RULE
    }
}
