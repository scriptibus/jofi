// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID

/** The applications context counts applications per company for the companies context (ADR-0041). */
class ApplicationCountsRepositoryTest {
    private val dsl = PostgresTestDatabase.migratedFromZero()
    private val rows = ApplicationRows(dsl)
    private val repository = ApplicationCountsRepository(dsl)

    @Test
    fun `counts the applications of the asked companies only`() {
        val acme = rows.company()
        val globex = rows.company()
        val initech = rows.company()
        repeat(2) { rows.application(UUID.randomUUID(), acme) }
        rows.application(UUID.randomUUID(), initech)

        val counts = repository.countByCompany(setOf(acme, globex)) as ApplicationCountsPort.Counts.Counted

        counts.byCompany shouldBe mapOf(acme to 2)
        counts.of(acme) shouldBe 2
        counts.of(globex) shouldBe 0
        repository.countByCompany(emptySet()) shouldBe ApplicationCountsPort.Counts.Counted(emptyMap())
    }

    @Test
    fun `a database it cannot reach is unavailable, not an exception`() {
        val broken = ApplicationCountsRepository(DSL.using(SQLDialect.POSTGRES))

        broken.countByCompany(setOf(UUID.randomUUID())) shouldBe ApplicationCountsPort.Counts.Unavailable
    }
}
