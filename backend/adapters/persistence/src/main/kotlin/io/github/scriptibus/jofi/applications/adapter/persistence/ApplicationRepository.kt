// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.github.scriptibus.jofi.shared.adapter.persistence.violatedConstraint
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Applications (`application`), their contact links (`application_contact`) and status history
 * (`application_status_change`), in the caller's transaction. **No write overwrites columns it does not
 * own** (ADR-0041): details, contact links, status and the unread flag are separate writes, the versioned
 * ones stored only on top of the version they were based on. Foreign-key violations are mapped by
 * constraint name; notes and pay are personal, so only operations and exception types are logged.
 */
@Component
class ApplicationRepository(
    private val dsl: DSLContext,
) : ApplicationRepositoryPort {
    private val tables = ApplicationTables(dsl)

    override fun add(
        application: Application,
        initial: StatusChange,
    ): ApplicationStoreResult<Unit> =
        storeCall("add") {
            dsl.insertInto(APPLICATION).set(ApplicationRecords.toRecord(application)).execute()
            tables.insertLinks(application.id, application.contacts)
            tables.appendHistory(initial)
            ApplicationStoreResult.Success(Unit)
        }

    override fun updateDetails(application: Application): ApplicationStoreResult<Unit> =
        storeCall("updateDetails") { tables.versioned(application, ApplicationRecords.detailsRecord(application)) {} }

    override fun replaceContacts(application: Application): ApplicationStoreResult<Unit> =
        storeCall("replaceContacts") {
            tables.versioned(application, ApplicationRecords.versionRecord(application)) {
                tables.replaceLinksIfChanged(application.id, application.contacts)
            }
        }

    override fun changeStatus(
        application: Application,
        change: StatusChange,
    ): ApplicationStoreResult<Unit> =
        storeCall("changeStatus") {
            tables.versioned(application, ApplicationRecords.statusRecord(application)) { tables.appendHistory(change) }
        }

    override fun statusHistory(id: ApplicationId): ApplicationStoreResult<List<StatusChange>> =
        storeCall("statusHistory") {
            if (tables.exists(
                    id,
                )
            ) {
                ApplicationStoreResult.Success(tables.history(id))
            } else {
                ApplicationStoreResult.NotFound
            }
        }

    override fun setUnread(
        id: ApplicationId,
        unread: Boolean,
    ): ApplicationStoreResult<Unit> =
        storeCall("setUnread") {
            val updated =
                dsl
                    .update(
                        APPLICATION,
                    ).set(APPLICATION.UNREAD, unread)
                    .where(APPLICATION.ID.eq(id.value))
                    .execute()
            if (updated == 0) ApplicationStoreResult.NotFound else ApplicationStoreResult.Success(Unit)
        }

    override fun findById(id: ApplicationId): ApplicationStoreResult<Application> =
        storeCall("findById") {
            dsl
                .fetchOne(APPLICATION, APPLICATION.ID.eq(id.value))
                ?.let {
                    ApplicationStoreResult.Success(
                        ApplicationRecords.toDomain(it, tables.linksOf(id), tables.sourcesOf(id)),
                    )
                }
                ?: ApplicationStoreResult.NotFound
        }

    /** One query for the page, one for the total, and one each for the page's contact links and sources. */
    override fun search(search: ApplicationSearch): ApplicationStoreResult<ApplicationPage<Application>> =
        storeCall("search") {
            val query = ApplicationQuery(search)
            val records =
                dsl
                    .select(APPLICATION.fields().toList())
                    .from(query.table)
                    .where(query.condition)
                    .orderBy(query.order)
                    .limit(search.size)
                    .offset(search.page.toLong() * search.size)
                    .fetchInto(APPLICATION)
            val ids = records.map { ApplicationId(it.id) }
            val links = tables.linksOf(ids)
            val sources = tables.sourcesOf(ids)
            val applications =
                records.map { ApplicationRecords.toDomain(it, links[it.id].orEmpty(), sources[it.id].orEmpty()) }
            ApplicationStoreResult.Success(
                ApplicationPage(applications, dsl.fetchCount(APPLICATION, query.condition).toLong()),
            )
        }

    override fun snapshotCount(id: ApplicationId): ApplicationStoreResult<Int> =
        storeCall("snapshotCount") {
            if (tables.exists(
                    id,
                )
            ) {
                ApplicationStoreResult.Success(tables.snapshotCount(id))
            } else {
                ApplicationStoreResult.NotFound
            }
        }

    override fun interviewCount(id: ApplicationId): ApplicationStoreResult<Int> =
        storeCall("interviewCount") {
            ApplicationStoreResult.Success(dsl.fetchCount(INTERVIEW, INTERVIEW.APPLICATION_ID.eq(id.value)))
        }

    override fun delete(
        id: ApplicationId,
        proof: ConfirmationResult.Confirmed,
    ): ApplicationStoreResult<Unit> {
        if (!proof.covers(Application.DELETE_OPERATION, id.value.toString())) return ApplicationStoreResult.NotConfirmed
        return storeCall("delete") {
            val deleted = dsl.deleteFrom(APPLICATION).where(APPLICATION.ID.eq(id.value)).execute()
            if (deleted == 0) ApplicationStoreResult.NotFound else ApplicationStoreResult.Success(Unit)
        }
    }
}

/**
 * No exception crosses the port. A missing company or contact is recognised by the name of the violated
 * foreign key (ADR-0041); messages can carry row values, so only the exception type is logged.
 */
private fun <T> storeCall(
    operation: String,
    block: () -> ApplicationStoreResult<T>,
): ApplicationStoreResult<T> =
    try {
        block()
    } catch (exception: RuntimeException) {
        when (exception.violatedConstraint()) {
            APPLICATION_COMPANY_FK -> {
                ApplicationStoreResult.CompanyNotFound
            }

            APPLICATION_CONTACT_CONTACT_FK -> {
                ApplicationStoreResult.ContactNotFound
            }

            else -> {
                log.error("Application store {} failed: {}", operation, exception.javaClass.name)
                ApplicationStoreResult.StorageFailure(operation)
            }
        }
    }

/** `application.company_id`: the application's company does not exist (any more). */
private const val APPLICATION_COMPANY_FK = "application_company_fk"

/** `application_contact.contact_id`: a linked contact does not exist (any more). */
private const val APPLICATION_CONTACT_CONTACT_FK = "application_contact_contact_fk"

private val log: Logger = LoggerFactory.getLogger(ApplicationRepository::class.java)
