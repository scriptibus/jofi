// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.time.Instant
import java.util.UUID

/** Identifies one task. */
@JvmInline
value class TaskId(
    val value: UUID,
) {
    /** How changelog entries refer to this task (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of tasks; never rename it, stored entries use it. */
        const val ENTITY_TYPE = "task"
    }
}

/**
 * Where a task comes from (spec §10.2). Never rename a kind: the database stores [Manual], [Chat] and [Suggested] as
 * `MANUAL`, `CHAT` and `SUGGESTED`.
 */
sealed interface TaskOrigin {
    /** A task someone created directly, rather than a rule suggesting it. */
    sealed interface Direct : TaskOrigin

    /** Created by the user in the app. */
    data object Manual : Direct

    /** Created through a conversation: the built-in chat or an external MCP client (#119). */
    data object Chat : Direct

    /**
     * Suggested by the [rule] (a kebab-case name such as `follow-up`, also the `Actor.System` name of the suggestion,
     * #95) for its [key], what the rule suggests it for (e.g. `application:<id>`). Rule and key identify a suggestion:
     * a rule suggests once per key, and a dismissed suggestion stays dismissed (`task_suggestion_unique`).
     */
    data class Suggested(
        val rule: String,
        val key: String,
    ) : TaskOrigin {
        init {
            require(rule.length <= MAX_RULE_LENGTH && RULE_NAME.matches(rule)) { "A rule name is kebab-case" }
            require(key.isNotEmpty() && key.length <= MAX_KEY_LENGTH) { "A suggestion key has 1 to $MAX_KEY_LENGTH" }
            require(key.none { it.isWhitespace() || it == '\u0000' }) { "A suggestion key has no whitespace" }
        }

        companion object {
            const val MAX_RULE_LENGTH = 64
            const val MAX_KEY_LENGTH = 200
            private val RULE_NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        }
    }
}

/**
 * Where a task is. [OPEN] and [DONE] tasks are the user's; [SUGGESTED] and [DISMISSED] exist only for suggestions,
 * which one click accepts (to [OPEN]) or dismisses. Never rename a constant: the database stores the names.
 */
enum class TaskState {
    SUGGESTED,
    OPEN,
    DONE,
    DISMISSED,
}

/**
 * The state changes of a task, each from exactly one state [from] to [to] (accept, dismiss, complete, reopen), so an
 * operation never does another one's job: reopening a suggestion or accepting a done task is not allowed.
 */
enum class TaskTransition(
    val from: TaskState,
    val to: TaskState,
) {
    ACCEPT(TaskState.SUGGESTED, TaskState.OPEN),
    DISMISS(TaskState.SUGGESTED, TaskState.DISMISSED),
    COMPLETE(TaskState.OPEN, TaskState.DONE),
    REOPEN(TaskState.DONE, TaskState.OPEN),
}

/**
 * Something the user wants to do (spec §10.2), with an exact or rough due time and optionally about an application,
 * company or contact. [version] counts changes, so edits by the user, the AI and external clients cannot overwrite
 * each other (ADR-0041); [completedAt] is set exactly while it is [TaskState.DONE]. [toString] shows no title and no
 * notes.
 */
data class Task(
    val id: TaskId,
    val details: TaskDetails,
    val origin: TaskOrigin,
    val state: TaskState,
    val completedAt: Instant?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(version >= INITIAL_VERSION) { "A task version must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "A task cannot be updated before it was created" }
        require((state == TaskState.DONE) == (completedAt != null)) { "A task has a completion time exactly when done" }
        require(origin is TaskOrigin.Suggested || state == TaskState.OPEN || state == TaskState.DONE) {
            "Only a suggestion is suggested or dismissed"
        }
    }

    /** The task with new [details], changed [at]; the same task (nothing to store or record) if they are unchanged. */
    fun edit(
        details: TaskDetails,
        at: Instant,
    ): Task = if (details == this.details) this else copy(details = details, version = version + 1, updatedAt = at)

    /**
     * The task after [transition] [at]: [TaskStateChange.Unchanged] if it is in the target state already (e.g. a done
     * task completed again), [TaskStateChange.NotAllowed] unless it is in the transition's source state. Callers
     * check the client's version before.
     */
    fun apply(
        transition: TaskTransition,
        at: Instant,
    ): TaskStateChange =
        when (state) {
            transition.to -> TaskStateChange.Unchanged
            transition.from -> TaskStateChange.Changed(moved(transition.to, at))
            else -> TaskStateChange.NotAllowed(state, transition.to)
        }

    private fun moved(
        target: TaskState,
        at: Instant,
    ): Task =
        copy(
            state = target,
            completedAt = at.takeIf { target == TaskState.DONE },
            version = version + 1,
            updatedAt = at,
        )

    override fun toString(): String = "Task(id=${id.value}, origin=$origin, state=$state, version=$version)"

    companion object {
        const val INITIAL_VERSION = 0L

        /** The confirmable operation (ADR-0039) of deleting tasks; its targets are task ids. */
        const val DELETE_OPERATION = "tasks.delete"

        /** A new open task with [details], created [at] by the user or through a chat. */
        fun create(
            id: TaskId,
            details: TaskDetails,
            origin: TaskOrigin.Direct,
            at: Instant,
        ): Task = Task(id, details, origin, TaskState.OPEN, null, INITIAL_VERSION, at, at)

        /** A new suggestion with [details], made [at] by [origin]'s rule; it waits for the user to accept it. */
        fun suggest(
            id: TaskId,
            details: TaskDetails,
            origin: TaskOrigin.Suggested,
            at: Instant,
        ): Task = Task(id, details, origin, TaskState.SUGGESTED, null, INITIAL_VERSION, at, at)
    }
}

/** Outcome of [Task.apply]. */
sealed interface TaskStateChange {
    /** The new version to store. */
    data class Changed(
        val task: Task,
    ) : TaskStateChange

    /** The task is in the transition's target state already: nothing to store, no changelog entry. */
    data object Unchanged : TaskStateChange

    /** The task cannot move [from] its state [to] the requested one. */
    data class NotAllowed(
        val from: TaskState,
        val to: TaskState,
    ) : TaskStateChange
}

/**
 * The groups of the task list (#94), by when a task is due as seen on the viewer's calendar: past its due time or
 * bucket, then today, the rest of this week, next week, the rest of this month, later, and someday.
 */
enum class TaskGroupKind { OVERDUE, TODAY, THIS_WEEK, NEXT_WEEK, THIS_MONTH, LATER, SOMEDAY }

/** One group of the task list: its [kind] and its [tasks], soonest first. */
data class TaskGroup(
    val kind: TaskGroupKind,
    val tasks: List<Task>,
)
