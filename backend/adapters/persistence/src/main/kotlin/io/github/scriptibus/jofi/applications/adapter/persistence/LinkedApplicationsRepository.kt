// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The applications context tells the companies context which applications a contact is linked to
 * (ADR-0041: applications depend on companies, never the reverse), as changelog references of its own
 * entity type. Served by `application_contact_contact_idx`.
 */
@Component
class LinkedApplicationsRepository(
    private val dsl: DSLContext,
) : LinkedApplicationsPort {
    override fun linkedTo(contact: UUID): LinkedApplicationsPort.Linked =
        try {
            val applications =
                dsl
                    .select(APPLICATION_CONTACT.APPLICATION_ID)
                    .from(APPLICATION_CONTACT)
                    .where(APPLICATION_CONTACT.CONTACT_ID.eq(contact))
                    .orderBy(APPLICATION_CONTACT.APPLICATION_ID)
                    .fetch(APPLICATION_CONTACT.APPLICATION_ID)
            LinkedApplicationsPort.Linked.Found(applications.map { ApplicationId(it).toEntityRef() })
        } catch (exception: RuntimeException) {
            log.error("Reading linked applications failed: {}", exception.javaClass.name)
            LinkedApplicationsPort.Linked.Unavailable
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(LinkedApplicationsRepository::class.java)
    }
}
