// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.persistence

import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactChannel
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ContactChannelRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ContactRecord
import java.time.ZoneOffset

/**
 * Maps contacts to `contact` and `contact_channel` rows and back, explicitly and without business
 * logic. A channel's row is its position in the contact's list (`position` from 0).
 */
internal object ContactRecords {
    fun toRecord(contact: Contact): ContactRecord =
        ContactRecord().apply {
            val details = contact.details
            id = contact.id.value
            companyId = details.company?.value
            name = details.name
            role = details.role
            relationshipNotes = details.relationshipNotes
            version = contact.version
            createdAt = contact.createdAt.atOffset(ZoneOffset.UTC)
            updatedAt = contact.updatedAt.atOffset(ZoneOffset.UTC)
        }

    fun channelRecords(contact: Contact): List<ContactChannelRecord> =
        contact.details.channels.mapIndexed { position, channel ->
            ContactChannelRecord().apply {
                contactId = contact.id.value
                this.position = position.toShort()
                kind = channel.kind.name
                value = channel.value
                label = channel.label
            }
        }

    /** The contact of [record] with [channels], which must be its own, ordered by position. */
    fun toDomain(
        record: ContactRecord,
        channels: List<ContactChannelRecord>,
    ): Contact =
        Contact(
            id = ContactId(record.id),
            details =
                ContactDetails(
                    name = record.name,
                    role = record.role,
                    company = record.companyId?.let(::CompanyId),
                    channels = channels.map { ContactChannel(ChannelKind.valueOf(it.kind), it.value, it.label) },
                    relationshipNotes = record.relationshipNotes,
                ),
            version = record.version,
            createdAt = record.createdAt.toInstant(),
            updatedAt = record.updatedAt.toInstant(),
        )
}
