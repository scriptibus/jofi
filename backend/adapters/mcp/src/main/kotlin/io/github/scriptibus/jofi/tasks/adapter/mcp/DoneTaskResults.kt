// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.tasks.domain.Task
import java.time.Instant
import java.util.UUID

/**
 * What identifies a done task to a model: its title, [Untrusted] like every task text (ADR-0053, amendment of #119).
 * The notes are left out: a page of up to 50 tasks with 10,000-character notes would fill a context, and `reopen_task`
 * needs only the id and the version.
 */
data class DoneTaskWords(
    val title: String,
)

/** One done task of `list_done_tasks`; [version] is what `reopen_task` needs to be based on. */
data class DoneTaskResult(
    val id: UUID,
    val version: Long,
    val origin: TaskOriginKind,
    val link: TaskLinkResult?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val task: Untrusted<DoneTaskWords>,
) {
    companion object {
        fun from(task: Task): DoneTaskResult {
            val full = TaskDetailResult.from(task)
            return DoneTaskResult(
                full.id,
                full.version,
                full.origin,
                full.link,
                full.completedAt,
                full.createdAt,
                Untrusted(DoneTaskWords(task.details.title)),
            )
        }
    }
}

/** One page of the done tasks, the newest completion first; ask for the next `page` while [hasMore] is true. */
data class DoneTasksResult(
    val page: Int,
    val size: Int,
    val total: Int,
    val hasMore: Boolean,
    val tasks: List<DoneTaskResult>,
) {
    companion object {
        fun from(done: Paged<Task>) =
            DoneTasksResult(
                done.info.page,
                done.info.size,
                done.info.total,
                done.info.hasMore,
                done.items.map(DoneTaskResult::from),
            )
    }
}
