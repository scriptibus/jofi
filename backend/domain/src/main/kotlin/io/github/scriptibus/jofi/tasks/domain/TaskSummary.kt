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
        fun of(task: Task): TaskSummary =
            TaskSummary(
                task.id,
                task.details.title,
                task.details.timing,
                task.details.link,
                TextExcerpt.ofOrNull(task.details.notes),
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
         * The page of [groups] (the whole grouped list): the tasks are numbered through the groups in their order,
         * soonest first within each, and [request] picks a window of that sequence.
         */
        fun of(
            groups: List<TaskGroup>,
            request: PageRequest,
        ): TaskGroupsPage {
            val all = groups.flatMap { group -> group.tasks.map { group.kind to it } }
            val page = Paged.slice(all, request)
            val byKind = page.items.groupBy({ it.first }, { TaskSummary.of(it.second) })
            return TaskGroupsPage(
                groups.map { TaskSummaryGroup(it.kind, byKind[it.kind].orEmpty()) },
                PageInfo.of(request, all.size),
            )
        }
    }
}
