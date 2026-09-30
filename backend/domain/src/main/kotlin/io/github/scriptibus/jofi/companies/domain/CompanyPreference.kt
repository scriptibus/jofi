// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.DomainEvent
import java.time.Instant

/**
 * How the user feels about a company (spec §5). Favourites and blacklisted companies feed scanners
 * and knockouts; the optional [reason] tells the user later why they set it.
 */
sealed interface CompanyPreference {
    val kind: PreferenceKind
    val reason: String?

    data object None : CompanyPreference {
        override val kind = PreferenceKind.NONE
        override val reason: String? = null
    }

    data class Favourite(
        override val reason: String? = null,
    ) : CompanyPreference {
        override val kind = PreferenceKind.FAVOURITE

        init {
            requireReason(reason)
        }
    }

    data class Blacklisted(
        override val reason: String? = null,
    ) : CompanyPreference {
        override val kind = PreferenceKind.BLACKLISTED

        init {
            requireReason(reason)
        }
    }

    companion object {
        const val MAX_REASON_LENGTH = 1_000

        /** The preference of [kind]; [reason] is dropped for [PreferenceKind.NONE]. */
        fun of(
            kind: PreferenceKind,
            reason: String?,
        ): CompanyPreference =
            when (kind) {
                PreferenceKind.NONE -> None
                PreferenceKind.FAVOURITE -> Favourite(reason)
                PreferenceKind.BLACKLISTED -> Blacklisted(reason)
            }

        /** Whether [reason] is acceptable as a reason: absent, or trimmed, storable text of bounded length. */
        fun isValidReason(reason: String?): Boolean =
            reason == null ||
                (
                    reason.isNotBlank() && reason == reason.trim() && !reason.hasUnstorableCharacter() &&
                        reason.length <= MAX_REASON_LENGTH
                )
    }
}

/** The kinds of [CompanyPreference], e.g. to filter or store them. */
enum class PreferenceKind { NONE, FAVOURITE, BLACKLISTED }

private fun requireReason(reason: String?) {
    require(CompanyPreference.isValidReason(reason)) {
        "A preference reason must be trimmed, not blank and at most ${CompanyPreference.MAX_REASON_LENGTH} characters"
    }
}

/** Domain event: [actor] changed the preference of [company] from [before] to [after] at [occurredAt]. */
data class CompanyPreferenceChanged(
    val company: CompanyId,
    val before: CompanyPreference,
    val after: CompanyPreference,
    val actor: Actor,
    val occurredAt: Instant,
) : DomainEvent {
    init {
        require(before != after) { "A preference change must change the preference" }
    }
}
