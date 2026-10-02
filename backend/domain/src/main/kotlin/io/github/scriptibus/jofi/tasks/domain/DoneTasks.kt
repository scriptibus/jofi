// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

/**
 * One page of the done tasks, newest completion first (#235): a done task is in no other list, so this is the way
 * back to it. [page] counts from 0; a page holds 1 to [MAX_SIZE] tasks, so every caller, an AI client included, gets a
 * bounded answer however many tasks were ever completed.
 */
data class DoneTaskQuery(
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        require(page >= 0) { "A page number must not be negative" }
        require(size in 1..MAX_SIZE) { "A page holds 1 to $MAX_SIZE tasks" }
    }

    /** How many tasks come before this page. */
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 50

        /** The query for raw parameters, or `null` if [page] or [size] is out of range (the caller answers 400). */
        fun of(
            page: Int,
            size: Int,
        ): DoneTaskQuery? = if (page >= 0 && size in 1..MAX_SIZE) DoneTaskQuery(page, size) else null
    }
}

/** One page of done [tasks], the newest completion first, and the number [total] of all done tasks. */
data class DoneTaskPage(
    val tasks: List<Task>,
    val total: Long,
) {
    init {
        require(total >= tasks.size) { "The total cannot be smaller than the page" }
        require(tasks.all { it.state == TaskState.DONE }) { "A done page holds done tasks only" }
    }
}
