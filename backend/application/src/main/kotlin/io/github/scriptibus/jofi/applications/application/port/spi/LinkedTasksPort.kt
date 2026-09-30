// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.spi

import java.time.Instant
import java.util.UUID

/**
 * The tasks linked to an application, for its timeline (#87). The applications context asks, the tasks context
 * answers (ADR-0041: tasks depend on applications through this named interface, never the reverse), so this port
 * names applications and tasks by `UUID` and nests its types. Only the user's tasks count (open or done), not
 * suggestions that are pending or dismissed. Implementations never throw and never log titles.
 */
interface LinkedTasksPort {
    /**
     * Up to [count] tasks linked to [application], newest first by creation time, then by id (descending, in
     * PostgreSQL's `uuid` order), starting after [before] (from the newest without).
     */
    fun linkedTasks(
        application: UUID,
        before: Before?,
        count: Int,
    ): Tasks

    /** Tasks created before [createdAt], or at it with an id below [id]; without [id], only before [createdAt]. */
    data class Before(
        val createdAt: Instant,
        val id: UUID?,
    )

    /** A linked task; [completedAt] is set while it is done. [toString] leaves out the title. */
    data class LinkedTask(
        val id: UUID,
        val title: String,
        val createdAt: Instant,
        val completedAt: Instant?,
    ) {
        override fun toString(): String = "LinkedTask(id=$id, createdAt=$createdAt, completedAt=$completedAt)"
    }

    /**
     * Outcome of [linkedTasks]. A sealed class rather than an interface, since every interface in a port package is
     * a port (`*Port`, `SourceConventionsTest`).
     */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Tasks {
        data class Listed(
            val tasks: List<LinkedTask>,
        ) : Tasks()

        /** The tasks could not be read. */
        data object Unavailable : Tasks()
    }
}
