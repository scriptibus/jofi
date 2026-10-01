// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW_PARTICIPANT
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The applications context tells the companies context which applications a contact is linked to and
 * which interviews it takes part in (ADR-0041: applications depend on companies, never the reverse), as
 * changelog references of its own entity types. Served by `application_contact_contact_idx` and
 * `interview_participant_contact_idx`.
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
            val interviews =
                dsl
                    .select(INTERVIEW_PARTICIPANT.INTERVIEW_ID)
                    .from(INTERVIEW_PARTICIPANT)
                    .where(INTERVIEW_PARTICIPANT.CONTACT_ID.eq(contact))
                    .orderBy(INTERVIEW_PARTICIPANT.INTERVIEW_ID)
                    .fetch(INTERVIEW_PARTICIPANT.INTERVIEW_ID)
            LinkedApplicationsPort.Linked.Found(
                applications.map { ApplicationId(it).toEntityRef() },
                interviews.map { InterviewId(it).toEntityRef() },
            )
        } catch (exception: RuntimeException) {
            log.error("Reading linked applications failed: {}", exception.javaClass.name)
            LinkedApplicationsPort.Linked.Unavailable
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(LinkedApplicationsRepository::class.java)
    }
}
