// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import java.time.Instant

/**
 * A task as a list shows it (ADR-0056): everything but the full notes, of which only an [notesExcerpt] is kept. It
 * has no notes field, so a list entry cannot be mistaken for the task itself; reading one task gives the full text.
 * [toString] shows neither title nor notes.
 */
data class TaskSummary(
    val id: TaskId,
    val title: String,
    val timing: TaskTiming,
    val link: TaskLink?,
    val notesExcerpt: TextExcerpt?,
    val origin: TaskOrigin,
    val state: TaskState,
    val completedAt: Instant?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "TaskSummary(id=${id.value}, origin=$origin, state=$state, version=$version)"

    companion object {
        /**
         * [notes] are what the excerpt is cut from, given on purpose: the task's own for the user, the text after the
         * AI's filter for an AI (ADR-0056). There is no default, so no caller takes the unfiltered text by omission.
         */
        fun of(
            task: Task,
            notes: String?,
        ): TaskSummary =
            TaskSummary(
                task.id,
                task.details.title,
                task.details.timing,
                task.details.link,
                TextExcerpt.ofOrNull(notes),
                task.origin,
                task.state,
                task.completedAt,
                task.version,
                task.createdAt,
                task.updatedAt,
            )
    }
}

/** One group of a page of the task list: its [kind] and the [tasks] of the page that fall in it, soonest first. */
data class TaskSummaryGroup(
    val kind: TaskGroupKind,
    val tasks: List<TaskSummary>,
)

/**
 * One page of the grouped task list (ADR-0056): every [TaskGroupKind] in its order, each with the tasks of this page
 * only, empty ones included. [info] counts all open tasks, so a client knows what is left.
 */
data class TaskGroupsPage(
    val groups: List<TaskSummaryGroup>,
    val info: PageInfo,
) {
    companion object {
        /**
         * The window of [groups] (the whole grouped list) that [request] asks for: the tasks are numbered through the
         * groups in their order, soonest first within each. Summaries are made from [TaskWindow.tasks] by the caller,
         * since the notes' excerpts may need the AI's filter first.
         */
        fun window(
            groups: List<TaskGroup>,
            request: PageRequest,
        ): TaskWindow {
            val all = groups.flatMap { group -> group.tasks.map { group.kind to it } }
            return TaskWindow(groups.map { it.kind }, Paged.slice(all, request).items, PageInfo.of(request, all.size))
        }
    }
}

/** The tasks of one page with their groups, before their summaries are made. */
class TaskWindow(
    private val kinds: List<TaskGroupKind>,
    private val entries: List<Pair<TaskGroupKind, Task>>,
    private val info: PageInfo,
) {
    /** The tasks of the page, in the order [page] expects their summaries. */
    val tasks: List<Task> get() = entries.map { it.second }

    /** The page, from one summary per task of [tasks], in that order. */
    fun page(summaries: List<TaskSummary>): TaskGroupsPage {
        require(summaries.size == entries.size) { "One summary for each task of the window" }
        val byKind = entries.map { it.first }.zip(summaries).groupBy({ it.first }, { it.second })
        return TaskGroupsPage(kinds.map { TaskSummaryGroup(it, byKind[it].orEmpty()) }, info)
    }
}
