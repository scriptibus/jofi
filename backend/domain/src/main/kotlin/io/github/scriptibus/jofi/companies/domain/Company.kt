// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.time.Instant
import java.util.UUID

/** Identifies one company. */
@JvmInline
value class CompanyId(
    val value: UUID,
) {
    /** How changelog entries refer to this company (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of companies (spec §13); never rename it, stored entries use it. */
        const val ENTITY_TYPE = "company"
    }
}

/**
 * A company the user applies to or watches (spec §5). The user edits [details]; [profile] is the
 * AI-generated profile (later milestone); [preference] marks it as favourite or blacklisted.
 *
 * [version] counts changes: a change is stored only if the stored version is still the one it was
 * based on, so an edit by the user and one by an AI or external client cannot overwrite each other.
 */
data class Company(
    val id: CompanyId,
    val details: CompanyDetails,
    val profile: CompanyProfile?,
    val preference: CompanyPreference,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(version >= INITIAL_VERSION) { "A company version must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "A company cannot be updated before it was created" }
    }

    /**
     * The company with new [details], changed [at]; the same company (no new version, nothing to
     * record) if the details are unchanged. Callers check the client's version before, so a stale
     * edit is a conflict even when it would change nothing.
     */
    fun edit(
        details: CompanyDetails,
        at: Instant,
    ): Company = if (details == this.details) this else copy(details = details, version = version + 1, updatedAt = at)

    /**
     * The company with [preference], set by [actor] [at], and the event that announces it (scanners and
     * knockouts react to it), or `null` if the company already has that preference.
     */
    fun changePreference(
        preference: CompanyPreference,
        actor: Actor,
        at: Instant,
    ): PreferenceUpdate? {
        if (preference == this.preference) return null
        val changed = copy(preference = preference, version = version + 1, updatedAt = at)
        return PreferenceUpdate(changed, CompanyPreferenceChanged(id, this.preference, preference, actor, at))
    }

    companion object {
        const val INITIAL_VERSION = 0L

        /** The confirmable operation (ADR-0039) of deleting companies; its targets are company ids. */
        const val DELETE_OPERATION = "companies.delete"

        /** A new company without profile or preference. */
        fun create(
            id: CompanyId,
            details: CompanyDetails,
            at: Instant,
        ): Company = Company(id, details, null, CompanyPreference.None, INITIAL_VERSION, at, at)
    }
}

/** A company after a preference change, plus the event to publish once it is stored. */
data class PreferenceUpdate(
    val company: Company,
    val event: CompanyPreferenceChanged,
)

/**
 * The AI-generated company profile (spec §5: what they do, culture signals, news, public ratings) as
 * Markdown. A placeholder until profile generation lands; the user may edit it.
 */
data class CompanyProfile(
    val markdown: String,
    val generatedAt: Instant,
) {
    init {
        require(markdown.isNotBlank()) { "A company profile must not be blank" }
        require(markdown.length <= MAX_LENGTH) { "A company profile has at most $MAX_LENGTH characters" }
        require(!markdown.hasUnstorableCharacter()) { "A company profile cannot contain U+0000" }
    }

    companion object {
        const val MAX_LENGTH = 50_000
    }
}
