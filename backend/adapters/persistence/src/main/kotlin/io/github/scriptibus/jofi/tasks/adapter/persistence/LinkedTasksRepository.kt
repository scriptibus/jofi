// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.spi.LinkedTasksPort
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.TASK
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskState
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.util.UUID

/**
 * The tasks context answers the applications context which tasks are linked to an application (tasks depend on
 * applications' named interface `spi`, never the reverse, ADR-0041), both served by `task_application_idx`: for its
 * timeline (#87) the paged form of `TaskRepositoryPort.listByLink`, one keyset query over the user's tasks (open or
 * done); for its delete (#168) the ids of every linked task. Titles are never logged.
 */
@Component
class LinkedTasksRepository(
    private val dsl: DSLContext,
) : LinkedTasksPort {
    override fun linkedTasks(
        application: UUID,
        before: LinkedTasksPort.Before?,
        count: Int,
    ): LinkedTasksPort.Tasks =
        try {
            val tasks =
                dsl
                    .select(TASK.ID, TASK.TITLE, TASK.CREATED_AT, TASK.COMPLETED_AT)
                    .from(TASK)
                    .where(TASK.APPLICATION_ID.eq(application))
                    .and(TASK.STATE.`in`(TaskState.OPEN.name, TaskState.DONE.name))
                    .and(after(before))
                    .orderBy(TASK.CREATED_AT.desc(), TASK.ID.desc())
                    .limit(count)
                    .fetch { row ->
                        LinkedTasksPort.LinkedTask(
                            row[TASK.ID],
                            row[TASK.TITLE],
                            row[TASK.CREATED_AT].toInstant(),
                            row[TASK.COMPLETED_AT]?.toInstant(),
                        )
                    }
            LinkedTasksPort.Tasks.Listed(tasks)
        } catch (exception: RuntimeException) {
            log.error("Reading the tasks of an application failed: {}", exception.javaClass.name)
            LinkedTasksPort.Tasks.Unavailable
        }

    override fun linkedTo(application: UUID): LinkedTasksPort.Linked =
        try {
            val tasks =
                dsl
                    .select(TASK.ID)
                    .from(TASK)
                    .where(TASK.APPLICATION_ID.eq(application))
                    .orderBy(TASK.ID)
                    .fetch { row -> TaskId(row[TASK.ID]).toEntityRef() }
            LinkedTasksPort.Linked.Found(tasks)
        } catch (exception: RuntimeException) {
            log.error("Reading the tasks linked to an application failed: {}", exception.javaClass.name)
            LinkedTasksPort.Linked.Unavailable
        }

    private fun after(before: LinkedTasksPort.Before?): Condition {
        if (before == null) return DSL.noCondition()
        val at = before.createdAt.atOffset(ZoneOffset.UTC)
        return before.id?.let { DSL.row(TASK.CREATED_AT, TASK.ID).lt(at, it) } ?: TASK.CREATED_AT.lt(at)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(LinkedTasksRepository::class.java)
    }
}
