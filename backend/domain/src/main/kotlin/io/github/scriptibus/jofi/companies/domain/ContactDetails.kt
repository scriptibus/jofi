// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

/**
 * What the user keeps about a contact person (spec §5): third-party personal data (spec §13), so
 * [toString] shows no name, role, channel or note. Build it from untrusted input with
 * [ContactInput.validate]; the constructor only guards invariants and throws on a programming error.
 */
data class ContactDetails(
    val name: String,
    /** The contact's role, e.g. "Recruiter" or "Head of Engineering". */
    val role: String? = null,
    /** The company the contact belongs to; the contact is deleted with it (ADR-0041). */
    val company: CompanyId? = null,
    /** Ways to reach the contact in the user's order, without exact duplicates. */
    val channels: List<ContactChannel> = emptyList(),
    /** The user's notes on the relationship (how they met, what was said), Markdown. */
    val relationshipNotes: String? = null,
) {
    init {
        require(ContactRules.violations(name, role, channels, relationshipNotes).isEmpty()) {
            "Contact details break an invariant"
        }
    }

    override fun toString(): String = "ContactDetails(company=$company, channels=${channels.size})"

    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_ROLE_LENGTH = 200
        const val MAX_CHANNELS = 20
        const val MAX_NOTES_LENGTH = 50_000
    }
}

/**
 * One way to reach a contact: a [value] of its [kind], kept as entered, with an optional [label]
 * ("work", "mobile", "LinkedIn"). The rules accept every international format and leave the rest
 * to the user: an email address needs an `@` with text on both sides, a phone number a digit (no
 * E.164 normalisation, since numbers are often noted without a country code), a web link is a
 * [WebAddress].
 */
data class ContactChannel(
    val kind: ChannelKind,
    val value: String,
    val label: String? = null,
) {
    init {
        require(problemOf(kind, value) == null) { "A $kind channel value breaks an invariant" }
        require(label == null || isValidText(label, MAX_LABEL_LENGTH)) { "A channel label breaks an invariant" }
    }

    override fun toString(): String = "ContactChannel(kind=$kind)"

    companion object {
        const val MAX_EMAIL_LENGTH = 320
        const val MAX_PHONE_LENGTH = 64
        const val MAX_OTHER_LENGTH = 500
        const val MAX_LABEL_LENGTH = 100

        /** What is wrong with [value] as a channel of [kind], or `null` if it is fine. */
        fun problemOf(
            kind: ChannelKind,
            value: String,
        ): ViolationKind? =
            when {
                value.isBlank() || value != value.trim() -> ViolationKind.REQUIRED
                kind == ChannelKind.WEB -> ViolationKind.INVALID_URL.takeIf { WebAddress.parse(value) == null }
                value.length > maxLength(kind) -> ViolationKind.TOO_LONG
                kind == ChannelKind.EMAIL -> ViolationKind.INVALID_EMAIL.takeUnless { isEmail(value) }
                kind == ChannelKind.PHONE -> ViolationKind.INVALID_PHONE.takeUnless { isPhone(value) }
                else -> null
            }

        private fun maxLength(kind: ChannelKind): Int =
            when (kind) {
                ChannelKind.EMAIL -> MAX_EMAIL_LENGTH
                ChannelKind.PHONE -> MAX_PHONE_LENGTH
                ChannelKind.WEB -> WebAddress.MAX_LENGTH
                ChannelKind.OTHER -> MAX_OTHER_LENGTH
            }

        // The last `@` separates the domain; quoted local parts may contain one, so only its place is checked.
        private fun isEmail(value: String): Boolean {
            val at = value.lastIndexOf('@')
            return at > 0 && at < value.length - 1 && value.none(Char::isWhitespace)
        }

        // Any digits (not only ASCII), separators, extensions and vanity letters are fine.
        private fun isPhone(value: String): Boolean = value.any(Char::isDigit) && value.none(Char::isISOControl)
    }
}

/** How a [ContactChannel] reaches the contact. */
enum class ChannelKind {
    EMAIL,
    PHONE,

    /** A web link: a profile page (e.g. LinkedIn or XING) or a personal site. */
    WEB,

    /** Anything else, e.g. a messenger handle, as free text. */
    OTHER,
}

/**
 * A problem with one field of a [ContactInput], named so that clients can show it next to the field;
 * [channel] is the position of the channel in the input for the channel fields.
 */
data class ContactViolation(
    val field: ContactField,
    val problem: ViolationKind,
    val channel: Int? = null,
) {
    init {
        require((channel != null) == (field in CHANNEL_FIELDS)) { "Exactly the channel fields name a channel" }
        require(channel == null || channel >= 0) { "A channel position must not be negative" }
    }

    private companion object {
        val CHANNEL_FIELDS = setOf(ContactField.CHANNEL_VALUE, ContactField.CHANNEL_LABEL)
    }
}

enum class ContactField { NAME, ROLE, COMPANY, CHANNELS, CHANNEL_VALUE, CHANNEL_LABEL, RELATIONSHIP_NOTES }

/** The rules of [ContactDetails], shared by its invariants and [ContactInput.validate]. */
internal object ContactRules {
    fun violations(
        name: String,
        role: String?,
        channels: List<ContactChannel>,
        relationshipNotes: String?,
    ): List<ContactViolation> =
        listOfNotNull(
            text(ContactField.NAME, name, ContactDetails.MAX_NAME_LENGTH),
            role?.let { text(ContactField.ROLE, it, ContactDetails.MAX_ROLE_LENGTH) },
            relationshipNotes?.let { text(ContactField.RELATIONSHIP_NOTES, it, ContactDetails.MAX_NOTES_LENGTH) },
            ContactViolation(ContactField.CHANNELS, ViolationKind.TOO_MANY).takeIf {
                channels.size > ContactDetails.MAX_CHANNELS
            },
            ContactViolation(ContactField.CHANNELS, ViolationKind.REQUIRED).takeIf {
                channels.distinctBy { channel -> channel.kind to channel.value }.size != channels.size
            },
        )

    private fun text(
        field: ContactField,
        value: String,
        maxLength: Int,
    ): ContactViolation? =
        when {
            value.isBlank() || value != value.trim() -> ContactViolation(field, ViolationKind.REQUIRED)
            value.length > maxLength -> ContactViolation(field, ViolationKind.TOO_LONG)
            else -> null
        }
}

/** Whether [value] is trimmed, not blank and at most [maxLength] characters long. */
internal fun isValidText(
    value: String,
    maxLength: Int,
): Boolean = value.isNotBlank() && value == value.trim() && value.length <= maxLength
