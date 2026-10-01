// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.ApplicationDueDatesRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort.DueDate
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * The deadlines and offer answer dates of applications (#112), soonest first. Only ids, titles and dates are read; only
 * the exception type is logged.
 */
@Component
class ApplicationDueDatesRepository(
    private val dsl: DSLContext,
) : ApplicationDueDatesRepositoryPort {
    override fun deadlines(
        from: LocalDate,
        statuses: Set<ApplicationStatus>,
        limit: Int,
    ): ApplicationStoreResult<List<DueDate>> =
        dueDates("deadlines", APPLICATION.DEADLINE, APPLICATION.STATUS.`in`(statuses.map { it.name }), from, limit)

    override fun offerAnswers(
        from: LocalDate,
        limit: Int,
    ): ApplicationStoreResult<List<DueDate>> =
        dueDates(
            "offerAnswers",
            APPLICATION.OFFER_ANSWER_BY,
            APPLICATION.STATUS.eq(ApplicationStatus.OFFER.name),
            from,
            limit,
        )

    private fun dueDates(
        operation: String,
        date: Field<LocalDate?>,
        condition: Condition,
        from: LocalDate,
        limit: Int,
    ): ApplicationStoreResult<List<DueDate>> =
        try {
            val found =
                dsl
                    .select(APPLICATION.ID, APPLICATION.TITLE, date)
                    .from(APPLICATION)
                    .where(condition)
                    .and(date.ge(from))
                    .orderBy(date, APPLICATION.ID)
                    .limit(limit)
                    .fetch()
                    // The column is nullable; `ge` above already left out the rows without a date.
                    .mapNotNull { (id, title, day) -> day?.let { DueDate(id, title, it) } }
            ApplicationStoreResult.Success(found)
        } catch (exception: RuntimeException) {
            log.error("Reading application {} failed: {}", operation, exception.javaClass.name)
            ApplicationStoreResult.StorageFailure(operation)
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ApplicationDueDatesRepository::class.java)
    }
}
