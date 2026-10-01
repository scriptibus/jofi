// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactChannel
import io.github.scriptibus.jofi.companies.domain.ContactField
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactViolation
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import java.time.Instant
import java.util.UUID

/**
 * The contact tools' results. A contact is a third party's personal data. Every field a tool can write (name, role,
 * channels, relationship notes) is [Untrusted]: it may come from postings or emails, or from a prompt-injected model
 * that stores instructions for later sessions (ADR-0053, amendment of #119). The company link is an id.
 */
data class ContactWho(
    val name: String,
    val role: String?,
)

data class ContactSummary(
    val id: UUID,
    val companyId: UUID?,
    val contact: Untrusted<ContactWho>,
) {
    companion object {
        fun from(contact: Contact) =
            ContactSummary(
                contact.id.value,
                contact.details.company?.value,
                Untrusted(ContactWho(contact.details.name, contact.details.role)),
            )
    }
}

data class ContactSearchResult(
    val total: Long,
    val page: Int,
    val size: Int,
    val contacts: List<ContactSummary>,
) {
    companion object {
        fun from(
            page: CompanyPage<Contact>,
            number: Int,
            size: Int,
        ) = ContactSearchResult(page.total, number, size, page.items.map(ContactSummary::from))
    }
}

data class ChannelResult(
    val kind: ChannelKind,
    val value: String,
    val label: String?,
)

/** All writable fields, notes included: what `update_contact` takes back. */
data class ContactDetailFacts(
    val name: String,
    val role: String?,
    val channels: List<ChannelResult>,
    val relationshipNotes: String?,
)

/** One contact in full; [version] is what `update_contact` needs to be based on. */
data class ContactDetailResult(
    val id: UUID,
    val version: Long,
    val companyId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val contact: Untrusted<ContactDetailFacts>,
) {
    companion object {
        fun from(contact: Contact): ContactDetailResult {
            val details = contact.details
            return ContactDetailResult(
                contact.id.value,
                contact.version,
                details.company?.value,
                contact.createdAt,
                contact.updatedAt,
                Untrusted(
                    ContactDetailFacts(
                        details.name,
                        details.role,
                        details.channels.map(::channelOf),
                        details.relationshipNotes,
                    ),
                ),
            )
        }

        private fun channelOf(channel: ContactChannel) = ChannelResult(channel.kind, channel.value, channel.label)
    }
}

/** The tool errors of the contacts: stable codes, no stored content. */
internal object ContactToolErrors {
    fun failure(failure: ContactResult.Failure): ToolAnswer.Error =
        when (failure) {
            is ContactResult.Invalid -> {
                ToolAnswer.Error(
                    "invalid-arguments",
                    "The contact arguments are invalid.",
                    failure.violations.map(::problemOf),
                )
            }

            ContactResult.NotFound -> {
                ToolAnswer.Error("not-found", "No contact has this id.")
            }

            ContactResult.VersionConflict -> {
                ToolProblems.versionConflict()
            }

            is ContactResult.StorageFailure -> {
                ToolAnswer.Error("unavailable", "Contacts cannot be used now.")
            }

            // No tool of the contacts deletes, so this cannot happen; a new failure breaks this `when`.
            is ContactResult.Unconfirmed -> {
                ToolAnswer.Error("failed", "The contact request could not be completed.")
            }
        }

    /** `COMPANY` is the argument `companyId`; a channel's problem names its position, as `channels[0].value`. */
    private fun problemOf(violation: ContactViolation): ArgumentProblem {
        val code = ToolProblems.problemCode(violation.problem.name)
        val position = violation.channel
        return when {
            position != null -> ArgumentProblem("channels[$position].${channelPart(violation.field)}", code)
            violation.field == ContactField.COMPANY -> ArgumentProblem("companyId", code)
            else -> ArgumentProblem(ToolProblems.argumentName(violation.field.name), code)
        }
    }

    private fun channelPart(field: ContactField) = if (field == ContactField.CHANNEL_LABEL) "label" else "value"
}
