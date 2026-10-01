// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort.DueDate
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The deadlines and offer answer dates of the dashboard countdowns (#112) against the real schema. */
class ApplicationDueDatesRepositoryTest {
    private val dsl = PostgresTestDatabase.migratedFromZero()
    private val rows = ApplicationRows(dsl)
    private val repository = ApplicationDueDatesRepository(dsl)
    private val company = rows.company()
    private val today = LocalDate.parse("2026-10-01")
    private val waiting = setOf(ApplicationStatus.DISCOVERED, ApplicationStatus.SHORTLISTED)

    @Test
    fun `deadlines from today on of the statuses asked for come soonest first, at most the limit`() {
        val later = application("DISCOVERED", deadline = today.plusDays(9))
        val onToday = application("SHORTLISTED", deadline = today)
        val soon = application("DISCOVERED", deadline = today.plusDays(2))
        application("DISCOVERED", deadline = today.plusDays(30))
        application("DISCOVERED", deadline = today.minusDays(1))
        application("APPLIED", deadline = today.plusDays(1))
        application("DISCOVERED")

        repository.deadlines(today, waiting, 3) shouldBe
            ApplicationStoreResult.Success(
                listOf(due(onToday, today), due(soon, today.plusDays(2)), due(later, today.plusDays(9))),
            )
    }

    @Test
    fun `offer answer dates from today on of applications at offer come soonest first`() {
        val later = application("OFFER", answerBy = today.plusDays(5))
        val onToday = application("OFFER", answerBy = today)
        application("OFFER", answerBy = today.minusDays(1))
        application("ACCEPTED", answerBy = today.plusDays(1))
        application("OFFER")

        repository.offerAnswers(today, 50) shouldBe
            ApplicationStoreResult.Success(listOf(due(onToday, today), due(later, today.plusDays(5))))
    }

    @Test
    fun `a database it cannot reach is a storage failure, not an exception`() {
        val broken = ApplicationDueDatesRepository(DSL.using(SQLDialect.POSTGRES))

        broken.deadlines(today, waiting, 1) shouldBe ApplicationStoreResult.StorageFailure("deadlines")
        broken.offerAnswers(today, 1) shouldBe ApplicationStoreResult.StorageFailure("offerAnswers")
    }

    private fun application(
        status: String,
        deadline: LocalDate? = null,
        answerBy: LocalDate? = null,
    ): UUID {
        val id = UUID.randomUUID()
        rows.application(id, company) {
            this.status = status
            this.deadline = deadline
            offerAnswerBy = answerBy
        }
        return id
    }

    private fun due(
        id: UUID,
        date: LocalDate,
    ) = DueDate(id, "Backend Engineer", date)
}
