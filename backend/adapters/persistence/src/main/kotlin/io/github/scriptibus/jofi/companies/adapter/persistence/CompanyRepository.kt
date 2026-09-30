// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Companies (`company`). Changes store only on top of the version they were based on; the delete
 * needs the confirmation proof and maps the foreign key of applications by its name (ADR-0041).
 * Names can be personal data (a sole trader), so only operations and exception types are logged.
 */
@Component
class CompanyRepository(
    private val dsl: DSLContext,
) : CompanyRepositoryPort {
    override fun add(company: Company): CompanyStoreResult<Unit> =
        storeCall("add") {
            dsl.insertInto(COMPANY).set(CompanyRecords.toRecord(company)).execute()
            CompanyStoreResult.Success(Unit)
        }

    override fun update(company: Company): CompanyStoreResult<Unit> =
        storeCall("update") {
            val updated =
                dsl
                    .update(COMPANY)
                    .set(CompanyRecords.toRecord(company))
                    .where(COMPANY.ID.eq(company.id.value))
                    .and(COMPANY.VERSION.eq(company.version - 1))
                    .execute()
            when {
                updated == 1 -> CompanyStoreResult.Success(Unit)
                dsl.fetchExists(COMPANY, COMPANY.ID.eq(company.id.value)) -> CompanyStoreResult.VersionConflict
                else -> CompanyStoreResult.NotFound
            }
        }

    override fun findById(id: CompanyId): CompanyStoreResult<Company> =
        storeCall("findById") {
            dsl
                .fetchOne(COMPANY, COMPANY.ID.eq(id.value))
                ?.let { CompanyStoreResult.Success(CompanyRecords.toDomain(it)) }
                ?: CompanyStoreResult.NotFound
        }

    override fun search(search: CompanySearch): CompanyStoreResult<CompanyPage<Company>> =
        storeCall("search") {
            val query = NameQuery.of(search)
            val companies =
                dsl
                    .selectFrom(COMPANY)
                    .where(query.condition)
                    .orderBy(query.order)
                    .limit(search.size)
                    .offset(search.page.toLong() * search.size)
                    .fetch()
                    .map(CompanyRecords::toDomain)
            CompanyStoreResult.Success(CompanyPage(companies, dsl.fetchCount(COMPANY, query.condition).toLong()))
        }

    override fun findContactIds(id: CompanyId): CompanyStoreResult<List<ContactId>> =
        storeCall("findContactIds") {
            val ids =
                dsl
                    .select(CONTACT.ID)
                    .from(CONTACT)
                    .where(CONTACT.COMPANY_ID.eq(id.value))
                    .orderBy(CONTACT.ID)
                    .fetch(CONTACT.ID)
            CompanyStoreResult.Success(ids.map(::ContactId))
        }

    override fun delete(
        id: CompanyId,
        proof: ConfirmationResult.Confirmed,
    ): CompanyStoreResult<Unit> {
        if (!proof.covers(Company.DELETE_OPERATION, id.value.toString())) return CompanyStoreResult.NotConfirmed
        return storeCall("delete") {
            try {
                val deleted = dsl.deleteFrom(COMPANY).where(COMPANY.ID.eq(id.value)).execute()
                if (deleted == 0) CompanyStoreResult.NotFound else CompanyStoreResult.Success(Unit)
            } catch (exception: RuntimeException) {
                if (exception.violatedConstraint() == APPLICATION_COMPANY_FK) {
                    CompanyStoreResult.HasApplications
                } else {
                    throw exception
                }
            }
        }
    }

    /** No exception crosses the port; exception messages can carry row values, so only the type is logged. */
    private fun <T> storeCall(
        operation: String,
        block: () -> CompanyStoreResult<T>,
    ): CompanyStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            log.error("Company store {} failed: {}", operation, exception.javaClass.name)
            CompanyStoreResult.StorageFailure(operation)
        }

    private companion object {
        /** `application.company_id`, `ON DELETE RESTRICT`: the company still has applications. */
        const val APPLICATION_COMPANY_FK = "application_company_fk"

        val log: Logger = LoggerFactory.getLogger(CompanyRepository::class.java)
    }
}
