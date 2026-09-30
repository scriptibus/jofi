// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.companies.domain.ContactStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT_CHANNEL
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ContactChannelRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ContactRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.violatedConstraint
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Contacts (`contact`) and their channels (`contact_channel`), third-party personal data: only
 * operations and exception types are logged, never a row. Channels have no id of their own, so a
 * change replaces all of them (delete, then insert) after the version-checked update of the contact
 * row, in the caller's transaction. The delete needs the confirmation proof (ADR-0039) and removes
 * the contact row; its channels and application links go by `ON DELETE CASCADE`.
 */
@Component
class ContactRepository(
    private val dsl: DSLContext,
) : ContactRepositoryPort {
    override fun add(contact: Contact): ContactStoreResult<Unit> =
        storeCall("add") {
            dsl.insertInto(CONTACT).set(ContactRecords.toRecord(contact)).execute()
            insertChannels(contact)
            ContactStoreResult.Success(Unit)
        }

    override fun update(contact: Contact): ContactStoreResult<Unit> =
        storeCall("update") {
            val updated =
                dsl
                    .update(CONTACT)
                    .set(ContactRecords.toRecord(contact))
                    .where(CONTACT.ID.eq(contact.id.value))
                    .and(CONTACT.VERSION.eq(contact.version - 1))
                    .execute()
            when {
                updated == 1 -> {
                    dsl.deleteFrom(CONTACT_CHANNEL).where(CONTACT_CHANNEL.CONTACT_ID.eq(contact.id.value)).execute()
                    insertChannels(contact)
                    ContactStoreResult.Success(Unit)
                }

                dsl.fetchExists(CONTACT, CONTACT.ID.eq(contact.id.value)) -> {
                    ContactStoreResult.VersionConflict
                }

                else -> {
                    ContactStoreResult.NotFound
                }
            }
        }

    override fun findById(id: ContactId): ContactStoreResult<Contact> =
        storeCall("findById") {
            dsl
                .fetchOne(CONTACT, CONTACT.ID.eq(id.value))
                ?.let { ContactStoreResult.Success(withChannels(listOf(it)).single()) }
                ?: ContactStoreResult.NotFound
        }

    override fun search(search: ContactSearch): ContactStoreResult<CompanyPage<Contact>> =
        storeCall("search") {
            val query = NameQuery.of(search)
            val rows =
                dsl
                    .selectFrom(CONTACT)
                    .where(query.condition)
                    .orderBy(query.order)
                    .limit(search.size)
                    .offset(search.page.toLong() * search.size)
                    .fetch()
            ContactStoreResult.Success(
                CompanyPage(withChannels(rows), dsl.fetchCount(CONTACT, query.condition).toLong()),
            )
        }

    override fun delete(
        id: ContactId,
        proof: ConfirmationResult.Confirmed,
    ): ContactStoreResult<Unit> {
        if (!proof.covers(Contact.DELETE_OPERATION, id.value.toString())) return ContactStoreResult.NotConfirmed
        return storeCall("delete") {
            val deleted = dsl.deleteFrom(CONTACT).where(CONTACT.ID.eq(id.value)).execute()
            if (deleted == 0) ContactStoreResult.NotFound else ContactStoreResult.Success(Unit)
        }
    }

    private fun insertChannels(contact: Contact) {
        val channels = ContactRecords.channelRecords(contact)
        if (channels.isEmpty()) return
        dsl
            .insertInto(CONTACT_CHANNEL)
            .columns(CONTACT_CHANNEL.fields().toList())
            .valuesOfRecords(channels)
            .execute()
    }

    /** The contacts of [rows] in their order, each with its channels (one query for all of them). */
    private fun withChannels(rows: List<ContactRecord>): List<Contact> {
        if (rows.isEmpty()) return emptyList()
        val channels: Map<UUID, List<ContactChannelRecord>> =
            dsl
                .selectFrom(CONTACT_CHANNEL)
                .where(CONTACT_CHANNEL.CONTACT_ID.`in`(rows.map { it.id }))
                .orderBy(CONTACT_CHANNEL.CONTACT_ID, CONTACT_CHANNEL.POSITION)
                .fetch()
                .groupBy { it.contactId }
        return rows.map { ContactRecords.toDomain(it, channels[it.id].orEmpty()) }
    }

    /**
     * No exception crosses the port. A missing company is recognised by the name of the violated
     * constraint (ADR-0041); messages can carry row values, so only the exception type is logged.
     */
    private fun <T> storeCall(
        operation: String,
        block: () -> ContactStoreResult<T>,
    ): ContactStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            if (exception.violatedConstraint() == CONTACT_COMPANY_FK) {
                ContactStoreResult.CompanyNotFound
            } else {
                log.error("Contact store {} failed: {}", operation, exception.javaClass.name)
                ContactStoreResult.StorageFailure(operation)
            }
        }

    private companion object {
        /** `contact.company_id`: the contact's company does not exist (any more). */
        const val CONTACT_COMPANY_FK = "contact_company_fk"

        val log: Logger = LoggerFactory.getLogger(ContactRepository::class.java)
    }
}
