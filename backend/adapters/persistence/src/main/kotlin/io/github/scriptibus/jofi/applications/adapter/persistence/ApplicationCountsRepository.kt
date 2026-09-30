// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The applications context answers the companies context's question how many applications each company
 * has (ADR-0041: applications depend on companies, never the reverse). Served by `application_company_idx`.
 */
@Component
class ApplicationCountsRepository(
    private val dsl: DSLContext,
) : ApplicationCountsPort {
    override fun countByCompany(companies: Set<UUID>): ApplicationCountsPort.Counts =
        try {
            val count = DSL.count()
            val counts =
                dsl
                    .select(APPLICATION.COMPANY_ID, count)
                    .from(APPLICATION)
                    .where(APPLICATION.COMPANY_ID.`in`(companies))
                    .groupBy(APPLICATION.COMPANY_ID)
                    .fetchMap(APPLICATION.COMPANY_ID, count)
            ApplicationCountsPort.Counts.Counted(counts)
        } catch (exception: RuntimeException) {
            log.error("Counting applications failed: {}", exception.javaClass.name)
            ApplicationCountsPort.Counts.Unavailable
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ApplicationCountsRepository::class.java)
    }
}
