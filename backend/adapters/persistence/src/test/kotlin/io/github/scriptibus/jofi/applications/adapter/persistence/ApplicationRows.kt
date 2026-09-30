// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** Raw rows for the application schema tests: what a repository would write, without the domain. */
class ApplicationRows(
    private val dsl: DSLContext,
) {
    fun company(): UUID {
        val id = UUID.randomUUID()
        dsl
            .insertInto(COMPANY, COMPANY.ID, COMPANY.NAME, COMPANY.CREATED_AT, COMPANY.UPDATED_AT)
            .values(id, "ACME GmbH", NOW, NOW)
            .execute()
        return id
    }

    fun contact(company: UUID?): UUID {
        val id = UUID.randomUUID()
        dsl
            .insertInto(CONTACT, CONTACT.ID, CONTACT.COMPANY_ID, CONTACT.NAME, CONTACT.CREATED_AT, CONTACT.UPDATED_AT)
            .values(id, company, "Erika Mustermann", NOW, NOW)
            .execute()
        return id
    }

    fun application(
        id: UUID,
        company: UUID,
        customize: ApplicationRecord.() -> Unit = {},
    ) {
        dsl
            .newRecord(APPLICATION)
            .apply {
                this.id = id
                companyId = company
                title = "Backend Engineer"
                createdAt = NOW
                updatedAt = NOW
                customize()
            }.insert()
    }

    fun link(
        application: UUID,
        contact: UUID,
    ) {
        dsl
            .insertInto(APPLICATION_CONTACT, APPLICATION_CONTACT.APPLICATION_ID, APPLICATION_CONTACT.CONTACT_ID)
            .values(application, contact)
            .execute()
    }

    fun constraintsOf(table: String): List<String> =
        dsl
            .fetchValues(
                "select conname from pg_constraint where conrelid = ?::regclass and contype <> 'n' order by conname",
                table,
            ).map(Any?::toString)

    companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-09-30T08:00:00Z")

        /** The statement fails on exactly the named constraint (repositories map violations by name). */
        fun rejects(
            constraint: String,
            statement: () -> Unit,
        ) {
            shouldThrow<DataAccessException> { statement() }.message shouldContain "\"$constraint\""
        }
    }
}

/** A complete pay band in EUR per year from [source]; an estimate needs [basis] and [confidence] too. */
fun ApplicationRecord.payBand(
    min: BigDecimal? = BigDecimal("1000.00"),
    max: BigDecimal? = null,
    source: String = "POSTING",
    basis: String? = null,
    confidence: String? = null,
) {
    payMin = min
    payMax = max
    payCurrency = payCurrency ?: "EUR"
    payPeriod = payPeriod ?: "YEAR"
    paySource = paySource ?: source
    payEstimateBasis = payEstimateBasis ?: basis
    payEstimateConfidence = payEstimateConfidence ?: confidence
}

/** A complete offer salary, per year unless a period is set. */
fun ApplicationRecord.offerSalary(
    amount: BigDecimal = BigDecimal("1000.00"),
    currency: String = "EUR",
) {
    offerSalary = amount
    offerSalaryCurrency = currency
    offerSalaryPeriod = offerSalaryPeriod ?: "YEAR"
}
