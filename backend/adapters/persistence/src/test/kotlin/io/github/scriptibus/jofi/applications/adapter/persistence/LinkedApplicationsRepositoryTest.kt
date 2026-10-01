// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The applications context names the applications linked to a contact and the interviews it takes part in for the
 * companies context (ADR-0041).
 */
class LinkedApplicationsRepositoryTest {
    private val dsl = PostgresTestDatabase.migratedFromZero()
    private val rows = ApplicationRows(dsl)
    private val repository = LinkedApplicationsRepository(dsl)

    @Test
    fun `lists the applications linked to the contact only, in id order, as application refs`() {
        val company = rows.company()
        val erika = rows.contact(company)
        val max = rows.contact(company)
        val applications = List(3) { UUID.randomUUID() }.onEach { rows.application(it, company) }
        rows.link(applications[0], erika)
        rows.link(applications[2], erika)
        rows.link(applications[1], max)

        repository.linkedTo(erika) shouldBe
            LinkedApplicationsPort.Linked.Found(
                listOf(
                    applications[0],
                    applications[2],
                ).sortedBy { it.toString() }.map { EntityRef("application", it.toString()) },
            )
        repository.linkedTo(UUID.randomUUID()) shouldBe LinkedApplicationsPort.Linked.Found(emptyList())
    }

    @Test
    fun `lists the interviews the contact takes part in, across applications, in id order, as interview refs`() {
        val company = rows.company()
        val erika = rows.contact(company)
        val max = rows.contact(company)
        val applications = List(2) { UUID.randomUUID() }.onEach { rows.application(it, company) }
        val withErika =
            listOf(
                interview(applications[0], erika, max),
                interview(applications[1], erika),
            )
        interview(applications[0], max)

        repository.linkedTo(erika) shouldBe
            LinkedApplicationsPort.Linked.Found(
                emptyList(),
                withErika.map { it.value }.sortedBy { it.toString() }.map { EntityRef("interview", it.toString()) },
            )
    }

    private fun interview(
        application: UUID,
        vararg participants: UUID,
    ): InterviewId {
        val details =
            InterviewDetails(
                InterviewType.TECHNICAL,
                InterviewTime(Instant.parse("2026-10-05T08:00:00Z"), ZoneId.of("Europe/Berlin")),
                participants = participants.map(::ContactRef).toSet(),
            )
        val logged =
            Interview
                .log(InterviewId(UUID.randomUUID()), ApplicationId(application), details, Actor.User, Instant.EPOCH)
                .interview
        InterviewRepository(dsl).add(logged) shouldBe ApplicationStoreResult.Success(Unit)
        return logged.id
    }

    @Test
    fun `a database it cannot reach is unavailable, not an exception`() {
        val broken = LinkedApplicationsRepository(DSL.using(SQLDialect.POSTGRES))

        broken.linkedTo(UUID.randomUUID()) shouldBe LinkedApplicationsPort.Linked.Unavailable
    }
}
