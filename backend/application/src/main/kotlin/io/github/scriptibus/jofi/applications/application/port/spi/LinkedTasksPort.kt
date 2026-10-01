// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.spi

import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.time.Instant
import java.util.UUID

/**
 * The tasks linked to an application, for its timeline (#87) and its delete (#168). The applications context asks, the
 * tasks context answers (ADR-0041: tasks depend on applications through this named interface, never the reverse), so
 * this port names applications and tasks by `UUID` and nests its types. Implementations never throw and never log
 * titles.
 */
interface LinkedTasksPort {
    /**
     * Every task linked to [application], suggestions too, as changelog references built by the tasks context, in id
     * order. The application delete reads them in its transaction **before** it deletes, counts them in the
     * confirmation effect and writes one changelog entry per task; `task_application_fk` (`ON DELETE SET NULL`) then
     * clears the links (ADR-0049).
     */
    fun linkedTo(application: UUID): Linked

    /**
     * Up to [count] of the user's tasks (open or done, not suggestions that are pending or dismissed) linked to
     * [application], newest first by creation time, then by id (descending, in
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

    /** Outcome of [linkedTo]; a sealed class for the reason given at [Tasks]. */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Linked {
        data class Found(
            val tasks: List<EntityRef>,
        ) : Linked()

        /** The tasks could not be read; the delete answers a storage failure and deletes nothing. */
        data object Unavailable : Linked()
    }
}
