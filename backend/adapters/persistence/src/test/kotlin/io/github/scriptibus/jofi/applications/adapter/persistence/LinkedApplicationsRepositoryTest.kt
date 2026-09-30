// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID

/** The applications context names the applications linked to a contact for the companies context (ADR-0041). */
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
    fun `a database it cannot reach is unavailable, not an exception`() {
        val broken = LinkedApplicationsRepository(DSL.using(SQLDialect.POSTGRES))

        broken.linkedTo(UUID.randomUUID()) shouldBe LinkedApplicationsPort.Linked.Unavailable
    }
}
