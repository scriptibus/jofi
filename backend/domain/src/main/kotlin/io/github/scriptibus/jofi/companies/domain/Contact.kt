// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.time.Instant
import java.util.UUID

/** Identifies one contact person. */
@JvmInline
value class ContactId(
    val value: UUID,
) {
    /** How changelog entries refer to this contact (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of contacts (spec §13); never rename it, stored entries use it. */
        const val ENTITY_TYPE = "contact"
    }
}

/**
 * A contact person (spec §5): a recruiter, hiring manager or referrer, optionally at a company.
 * Third-party personal data (spec §13): the user can delete a contact at any time (with
 * confirmation, ADR-0039), it is exported, and [toString] shows no personal data. Interactions
 * (calls, interviews) and application links refer to it from their own contexts (#90, #92).
 *
 * [version] counts changes, as for [Company]: a change is stored only if the stored version is
 * still the one it was based on.
 */
data class Contact(
    val id: ContactId,
    val details: ContactDetails,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(version >= INITIAL_VERSION) { "A contact version must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "A contact cannot be updated before it was created" }
    }

    /**
     * The contact with new [details], changed [at]; the same contact (no new version, nothing to
     * record) if the details are unchanged. Callers check the client's version before.
     */
    fun edit(
        details: ContactDetails,
        at: Instant,
    ): Contact = if (details == this.details) this else copy(details = details, version = version + 1, updatedAt = at)

    override fun toString(): String = "Contact(id=${id.value}, version=$version)"

    companion object {
        const val INITIAL_VERSION = 0L

        /** The confirmable operation (ADR-0039) of deleting contacts; its targets are contact ids. */
        const val DELETE_OPERATION = "contacts.delete"

        fun create(
            id: ContactId,
            details: ContactDetails,
            at: Instant,
        ): Contact = Contact(id, details, INITIAL_VERSION, at, at)
    }
}

/**
 * Domain event: [actor] deleted [contact] at [occurredAt], alone or with its company. Application
 * links, interview participants and task links react to it (#90, #92, #93). It carries no personal
 * data, only the id.
 */
data class ContactDeleted(
    val contact: ContactId,
    val actor: Actor,
    val occurredAt: Instant,
)
