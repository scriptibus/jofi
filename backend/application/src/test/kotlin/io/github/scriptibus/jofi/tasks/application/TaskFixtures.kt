// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.RedactForAiUseCase
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * An in-memory task store behind every port the task use cases take, with a transaction that restores the tasks and
 * the changelog when the result is not committed. Like the database, it refuses links to unknown entities
 * (`task_*_fk`) and stores only on top of the version a change was based on.
 */
class TaskFixtures {
    val tasks = linkedMapOf<TaskId, Task>()
    val links = mutableSetOf<TaskLink>(APPLICATION)
    val entries = mutableListOf<ChangelogEntry>()
    var failingChangelog = false
    var failingStore = false

    /** What the "never send to AI" source flags; `null` makes it unavailable. */
    var flaggedValues: Set<FlaggedValue>? = emptySet()

    val redaction =
        RedactForAiUseCase(
            object : AiVisibilityPort {
                override fun rulesFor(sources: Set<ContentSource>) =
                    flaggedValues
                        ?.let { AiVisibilityResult.Known(NeverSendRules(emptyMap(), it)) }
                        ?: AiVisibilityResult.Unavailable("test")
            },
        )

    /** A version another client stored between this use case's read and its write (the update race). */
    var concurrentVersion: Long? = null

    val repository =
        object : TaskRepositoryPort {
            override fun add(task: Task): TaskStoreResult<Unit> =
                when {
                    failingStore -> {
                        TaskStoreResult.StorageFailure("add")
                    }

                    !linkExists(task) -> {
                        TaskStoreResult.LinkNotFound
                    }

                    task.origin is TaskOrigin.Suggested && tasks.values.any { it.origin == task.origin } -> {
                        TaskStoreResult.SuggestionExists
                    }

                    else -> {
                        TaskStoreResult.Success(Unit).also { tasks[task.id] = task }
                    }
                }

            override fun update(task: Task): TaskStoreResult<Unit> {
                val stored = tasks[task.id]
                return when {
                    stored == null -> TaskStoreResult.NotFound
                    (concurrentVersion ?: stored.version) != task.version - 1 -> TaskStoreResult.VersionConflict
                    !linkExists(task) -> TaskStoreResult.LinkNotFound
                    else -> TaskStoreResult.Success(Unit).also { tasks[task.id] = task }
                }
            }

            override fun findById(id: TaskId): TaskStoreResult<Task> =
                when {
                    failingStore -> TaskStoreResult.StorageFailure("findById")
                    else -> tasks[id]?.let { TaskStoreResult.Success(it) } ?: TaskStoreResult.NotFound
                }

            override fun listByState(state: TaskState): TaskStoreResult<List<Task>> =
                when {
                    failingStore -> TaskStoreResult.StorageFailure("listByState")
                    else -> TaskStoreResult.Success(tasks.values.filter { it.state == state }.sortedBy { it.createdAt })
                }

            override fun pageByStateNewestFirst(
                state: TaskState,
                request: PageRequest,
            ): TaskStoreResult<Paged<Task>> =
                when {
                    failingStore -> {
                        TaskStoreResult.StorageFailure("pageByStateNewestFirst")
                    }

                    else -> {
                        val newestFirst =
                            tasks.values
                                .filter { it.state == state }
                                .sortedBy { it.createdAt }
                                .reversed()
                        TaskStoreResult.Success(Paged.slice(newestFirst, request))
                    }
                }

            override fun listDone(request: PageRequest): TaskStoreResult<Paged<Task>> {
                if (failingStore) return TaskStoreResult.StorageFailure("listDone")
                val done =
                    tasks.values
                        .filter { it.state == TaskState.DONE }
                        .sortedWith(
                            compareByDescending<Task> { it.completedAt }.thenByDescending { it.id.value.toString() },
                        )
                return TaskStoreResult.Success(Paged.slice(done, request))
            }

            override fun listByLink(link: TaskLink): TaskStoreResult<List<Task>> = error("Not used by these use cases")

            override fun delete(
                id: TaskId,
                proof: ConfirmationResult.Confirmed,
            ): TaskStoreResult<Unit> =
                when {
                    !proof.covers(Task.DELETE_OPERATION, id.value.toString()) -> TaskStoreResult.NotConfirmed
                    tasks.remove(id) == null -> TaskStoreResult.NotFound
                    else -> TaskStoreResult.Success(Unit)
                }
        }

    val changelog =
        object : ChangelogPort {
            override fun append(entry: ChangelogEntry): ChangelogResult<Unit> {
                if (failingChangelog) return ChangelogResult.StorageFailure("append")
                entries += entry
                return ChangelogResult.Success(Unit)
            }

            override fun listByEntity(
                entity: EntityRef,
                limit: ChangelogLimit,
            ) = ChangelogResult.Success(entries.filter { it.entity == entity })

            override fun listRecent(limit: ChangelogLimit) = ChangelogResult.Success(entries.toList())
        }

    val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T {
                val tasksBefore = tasks.toMap()
                val entriesBefore = entries.toList()
                val result = work()
                if (!commitIf(result)) {
                    tasks.clear()
                    tasks.putAll(tasksBefore)
                    entries.clear()
                    entries.addAll(entriesBefore)
                }
                return result
            }
        }

    val confirmation = ConfirmActionUseCase(TokenStore(), CLOCK, Duration.ofMinutes(5))

    /** A stored open task, created by the user before [NOW]. */
    fun task(
        title: String = "Call back",
        timing: TaskTiming = TaskTiming.Bucket.SOMEDAY,
        link: TaskLink? = null,
    ): Task {
        val task = Task.create(TaskId(UUID.randomUUID()), TaskDetails(title, timing, link), TaskOrigin.Manual, CREATED)
        tasks[task.id] = task
        return task
    }

    private fun linkExists(task: Task): Boolean = task.details.link.let { it == null || it in links }

    private class TokenStore : ConfirmationStorePort {
        private val pending = mutableMapOf<String, PendingConfirmation>()

        override fun issue(
            pending: PendingConfirmation,
            now: Instant,
        ): ConfirmationToken {
            val token = "token-${this.pending.size + 1}-${System.nanoTime()}"
            this.pending[token] = pending
            return ConfirmationToken(token)
        }

        override fun redeem(token: ConfirmationToken): PendingConfirmation? = pending.remove(token.value)
    }

    companion object {
        val APPLICATION = ApplicationRef(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
        val CREATED: Instant = Instant.parse("2026-09-01T08:00:00Z")

        /**
         * Still Wednesday 30 September 2026 in Berlin (23:30), already Thursday 1 October in Tokyo (06:30); with
         * nanoseconds the use cases must cut to the microseconds `timestamptz` keeps.
         */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-30T21:30:00.123456789Z"), ZoneOffset.UTC)

        /** [CLOCK] cut to the microseconds `timestamptz` keeps. */
        val NOW: Instant = Instant.parse("2026-09-30T21:30:00.123456Z")
    }
}
