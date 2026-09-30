// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

/** Result of validating untrusted contact input: the domain value, or every problem found. */
sealed interface ContactValidation<out T> {
    data class Valid<out T>(
        val value: T,
    ) : ContactValidation<T>

    data class Invalid(
        val violations: List<ContactViolation>,
    ) : ContactValidation<Nothing> {
        init {
            require(violations.isNotEmpty()) { "An invalid input names at least one violation" }
        }
    }
}

/**
 * Contact details as the user, the AI or an external client entered them. [validate] normalizes text
 * to Unicode NFC and trims it, treats blank optional fields as absent, drops channels with a blank
 * value and exact duplicates, and reports what is still wrong. Whether [company] exists is the use
 * case's check. [toString] shows no personal data.
 */
data class ContactInput(
    val name: String,
    val role: String? = null,
    val company: CompanyId? = null,
    val channels: List<ChannelInput> = emptyList(),
    val relationshipNotes: String? = null,
) {
    fun validate(): ContactValidation<ContactDetails> {
        val name = name.normalized().trim()
        val role = role.trimmedOrNull()
        val relationshipNotes = relationshipNotes.trimmedOrNull()
        val parsed = channels.mapIndexedNotNull { position, channel -> channel.parse(position) }
        val channels = parsed.mapNotNull(ParsedChannel::channel).distinctBy { it.kind to it.value }
        val violations =
            parsed.flatMap(ParsedChannel::violations) +
                ContactRules.violations(name, role, channels, relationshipNotes)
        if (violations.isNotEmpty()) return ContactValidation.Invalid(violations)
        return ContactValidation.Valid(ContactDetails(name, role, company, channels, relationshipNotes))
    }

    override fun toString(): String = "ContactInput(company=$company, channels=${channels.size})"
}

/** One channel as entered; [toString] shows only its kind. */
data class ChannelInput(
    val kind: ChannelKind,
    val value: String,
    val label: String? = null,
) {
    /** The channel at [position] of the input, its violations, or `null` if its value is blank. */
    internal fun parse(position: Int): ParsedChannel? {
        val value = value.trimmedOrNull() ?: return null
        val label = label.trimmedOrNull()
        val violations =
            listOfNotNull(
                ContactChannel.problemOf(kind, value)?.let {
                    ContactViolation(ContactField.CHANNEL_VALUE, it, position)
                },
                label?.let { labelProblem(it) }?.let { ContactViolation(ContactField.CHANNEL_LABEL, it, position) },
            )
        return ParsedChannel(if (violations.isEmpty()) ContactChannel(kind, value, label) else null, violations)
    }

    override fun toString(): String = "ChannelInput(kind=$kind)"

    private fun labelProblem(label: String): ViolationKind? =
        when {
            label.hasUnstorableCharacter() -> ViolationKind.INVALID_CHARACTER
            label.length > ContactChannel.MAX_LABEL_LENGTH -> ViolationKind.TOO_LONG
            else -> null
        }
}

internal class ParsedChannel(
    val channel: ContactChannel?,
    val violations: List<ContactViolation>,
)
