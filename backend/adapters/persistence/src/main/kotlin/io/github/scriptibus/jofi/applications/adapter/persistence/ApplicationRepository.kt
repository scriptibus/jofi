// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationRecord
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
    override fun add(
        application: Application,
        initial: StatusChange,
    ): ApplicationStoreResult<Unit> =
        storeCall("add") {
            dsl.insertInto(APPLICATION).set(ApplicationRecords.toRecord(application)).execute()
            insertLinks(application.id, application.contacts)
            appendHistory(initial)
            ApplicationStoreResult.Success(Unit)
        }

    override fun updateDetails(application: Application): ApplicationStoreResult<Unit> =
        storeCall("updateDetails") { versioned(application, ApplicationRecords.detailsRecord(application)) {} }

    override fun replaceContacts(application: Application): ApplicationStoreResult<Unit> =
        storeCall("replaceContacts") {
            versioned(application, ApplicationRecords.versionRecord(application)) {
                if (linksOf(application.id) != application.contacts) {
                    dsl
                        .deleteFrom(APPLICATION_CONTACT)
                        .where(APPLICATION_CONTACT.APPLICATION_ID.eq(application.id.value))
                        .execute()
                    insertLinks(application.id, application.contacts)
                }
            }
        }

    override fun changeStatus(
        application: Application,
        change: StatusChange,
    ): ApplicationStoreResult<Unit> =
        storeCall("changeStatus") {
            versioned(application, ApplicationRecords.statusRecord(application)) { appendHistory(change) }
        }

    override fun statusHistory(id: ApplicationId): ApplicationStoreResult<List<StatusChange>> =
        storeCall("statusHistory") {
            if (!exists(id)) {
                ApplicationStoreResult.NotFound
            } else {
                val entries =
                    dsl
                        .selectFrom(APPLICATION_STATUS_CHANGE)
                        .where(APPLICATION_STATUS_CHANGE.APPLICATION_ID.eq(id.value))
                        .orderBy(APPLICATION_STATUS_CHANGE.ID)
                        .fetch()
                ApplicationStoreResult.Success(entries.map(ApplicationRecords::toDomain))
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
                ?.let { ApplicationStoreResult.Success(ApplicationRecords.toDomain(it, linksOf(id))) }
                ?: ApplicationStoreResult.NotFound
        }

    /** The list with its filters and ranking is #83; until then its endpoint answers 501 and nothing calls this. */
    override fun search(search: ApplicationSearch): ApplicationStoreResult<ApplicationPage<Application>> =
        ApplicationStoreResult.StorageFailure("search")

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

    /**
     * Writes [record]'s columns only if the stored version is one below [application]'s, then runs
     * [andThen]; otherwise tells a stale version from a missing application.
     */
    private fun versioned(
        application: Application,
        record: ApplicationRecord,
        andThen: () -> Unit,
    ): ApplicationStoreResult<Unit> {
        val updated =
            dsl
                .update(APPLICATION)
                .set(record)
                .where(APPLICATION.ID.eq(application.id.value))
                .and(APPLICATION.VERSION.eq(application.version - 1))
                .execute()
        return when {
            updated == 1 -> ApplicationStoreResult.Success(Unit).also { andThen() }
            exists(application.id) -> ApplicationStoreResult.VersionConflict
            else -> ApplicationStoreResult.NotFound
        }
    }

    private fun exists(id: ApplicationId): Boolean = dsl.fetchExists(APPLICATION, APPLICATION.ID.eq(id.value))

    private fun linksOf(id: ApplicationId): Set<ContactRef> =
        dsl
            .select(APPLICATION_CONTACT.CONTACT_ID)
            .from(APPLICATION_CONTACT)
            .where(APPLICATION_CONTACT.APPLICATION_ID.eq(id.value))
            .fetch(APPLICATION_CONTACT.CONTACT_ID)
            .mapTo(mutableSetOf(), ::ContactRef)

    private fun insertLinks(
        id: ApplicationId,
        contacts: Set<ContactRef>,
    ) {
        if (contacts.isEmpty()) return
        val insert =
            dsl.insertInto(
                APPLICATION_CONTACT,
                APPLICATION_CONTACT.APPLICATION_ID,
                APPLICATION_CONTACT.CONTACT_ID,
            )
        contacts.fold(insert) { statement, contact -> statement.values(id.value, contact.value) }.execute()
    }

    private fun appendHistory(change: StatusChange) {
        dsl.insertInto(APPLICATION_STATUS_CHANGE).set(ApplicationRecords.toRecord(change)).execute()
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

    private companion object {
        /** `application.company_id`: the application's company does not exist (any more). */
        const val APPLICATION_COMPANY_FK = "application_company_fk"

        /** `application_contact.contact_id`: a linked contact does not exist (any more). */
        const val APPLICATION_CONTACT_CONTACT_FK = "application_contact_contact_fk"

        val log: Logger = LoggerFactory.getLogger(ApplicationRepository::class.java)
    }
}
