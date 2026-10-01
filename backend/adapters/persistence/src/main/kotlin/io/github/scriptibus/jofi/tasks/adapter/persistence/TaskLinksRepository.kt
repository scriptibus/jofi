// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.companies.application.port.spi.TaskLinksPort
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.TASK
import io.github.scriptibus.jofi.tasks.domain.TaskId
import org.jooq.DSLContext
import org.jooq.Field
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The tasks context answers the companies context which tasks are linked to the companies and contacts a delete
 * removes (#168; tasks depend on companies' named interface `spi`, never the reverse, ADR-0041, ADR-0049). Ids only,
 * served by `task_company_idx` and `task_contact_idx`; every task counts, suggestions too.
 */
@Component
class TaskLinksRepository(
    private val dsl: DSLContext,
) : TaskLinksPort {
    override fun linkedTo(
        companies: Set<UUID>,
        contacts: Set<UUID>,
    ): TaskLinksPort.Links =
        try {
            TaskLinksPort.Links.Found(linkedBy(TASK.COMPANY_ID, companies), linkedBy(TASK.CONTACT_ID, contacts))
        } catch (exception: RuntimeException) {
            log.error("Reading the tasks linked to companies or contacts failed: {}", exception.javaClass.name)
            TaskLinksPort.Links.Unavailable
        }

    private fun linkedBy(
        link: Field<UUID>,
        targets: Set<UUID>,
    ): List<TaskLinksPort.LinkedTask> {
        if (targets.isEmpty()) return emptyList()
        return dsl
            .select(link, TASK.ID)
            .from(TASK)
            .where(link.`in`(targets))
            .orderBy(link, TASK.ID)
            .fetch { row -> TaskLinksPort.LinkedTask(row[link], TaskId(row[TASK.ID]).toEntityRef()) }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(TaskLinksRepository::class.java)
    }
}
