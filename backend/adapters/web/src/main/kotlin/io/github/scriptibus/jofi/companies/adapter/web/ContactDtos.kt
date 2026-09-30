// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.ChannelInput
import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactInput
import java.time.Instant
import java.util.UUID

// Contacts are third-party personal data (spec §13): every DTO that holds some prints none of it.

/** How a channel reaches a contact (the API's copy of the domain's `ChannelKind`). */
enum class ContactChannelKind {
    EMAIL,
    PHONE,

    /** A profile page (LinkedIn, XING) or personal site: an absolute http(s) URL. */
    WEB,

    /** Free text, e.g. a messenger handle. */
    OTHER,
    ;

    fun toDomain(): ChannelKind = ChannelKind.valueOf(name)

    companion object {
        fun from(kind: ChannelKind): ContactChannelKind = valueOf(kind.name)
    }
}

/** One way to reach a contact, with an optional [label] such as "work" or "mobile". */
data class ContactChannelDto(
    val kind: ContactChannelKind,
    val value: String,
    val label: String? = null,
) {
    fun toInput(): ChannelInput = ChannelInput(kind.toDomain(), value, label)

    override fun toString(): String = "ContactChannelDto(kind=$kind)"
}

/**
 * What the user edits about a contact. Text is trimmed, blank optional fields count as absent, and
 * channels with a blank value or an exact duplicate are dropped; a violation answers 400, naming a
 * channel's field as `channels[<position in this request>].value` or `.label`.
 */
data class ContactDetailsRequest(
    val name: String,
    val role: String? = null,
    /** The contact's company; one that does not exist is a 400 (`companyId`, `NOT_FOUND`). */
    val companyId: UUID? = null,
    val channels: List<ContactChannelDto>? = null,
    /** Markdown. */
    val relationshipNotes: String? = null,
) {
    fun toInput(): ContactInput =
        ContactInput(
            name,
            role,
            companyId?.let(::CompanyId),
            channels.orEmpty().map(ContactChannelDto::toInput),
            relationshipNotes,
        )

    override fun toString(): String = "ContactDetailsRequest(companyId=$companyId)"
}

/** Body of `PUT /api/contacts/{id}`; [basedOnVersion] is the `version` the client last read. */
data class UpdateContactRequest(
    val details: ContactDetailsRequest,
    val basedOnVersion: Long,
)

/** One contact. [version] goes back as `basedOnVersion` with the next change; render the notes sanitised. */
data class ContactResponse(
    val id: UUID,
    val name: String,
    val role: String?,
    val companyId: UUID?,
    val channels: List<ContactChannelDto>,
    val relationshipNotes: String?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String = "ContactResponse(id=$id, version=$version)"

    companion object {
        fun from(contact: Contact): ContactResponse =
            with(contact.details) {
                ContactResponse(
                    id = contact.id.value,
                    name = name,
                    role = role,
                    companyId = company?.value,
                    channels = channels.map { ContactChannelDto(ContactChannelKind.from(it.kind), it.value, it.label) },
                    relationshipNotes = relationshipNotes,
                    version = contact.version,
                    createdAt = contact.createdAt,
                    updatedAt = contact.updatedAt,
                )
            }
    }
}

/** JSON body of `GET /api/contacts`: one page of contacts. */
data class ContactPageResponse(
    val contacts: List<ContactResponse>,
    val page: Int,
    val size: Int,
    /** All contacts matching the search, across pages. */
    val total: Long,
) {
    companion object {
        fun from(
            page: CompanyPage<Contact>,
            number: Int,
            size: Int,
        ): ContactPageResponse = ContactPageResponse(page.items.map(ContactResponse::from), number, size, page.total)
    }
}
