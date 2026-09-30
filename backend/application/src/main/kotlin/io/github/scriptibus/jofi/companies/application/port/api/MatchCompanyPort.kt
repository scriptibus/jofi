// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application.port.api

import io.github.scriptibus.jofi.shared.domain.Actor
import java.util.UUID

/**
 * The company a job posting names, for the posting import of the applications context (#96): an existing company
 * whose name matches (a fuzzy name search, then `CompanyNameKey` equality), otherwise a new company with just that
 * name, created by [execute]'s actor with its changelog entry. Creating is not destructive and the user sees the
 * company, so it needs no confirmation. Part of the named interface `api` of the companies context: only plain values
 * cross it. Never throws.
 */
interface MatchCompanyPort {
    fun execute(
        name: String,
        actor: Actor,
    ): Match

    /** Outcome of [execute]. A sealed class, since every interface in a port package is a port. */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Match {
        abstract val id: UUID?

        data class Found(
            override val id: UUID,
        ) : Match()

        data class Created(
            override val id: UUID,
        ) : Match()

        /** The name is no valid company name (blank, too long); nothing was created. */
        data object InvalidName : Match() {
            override val id: UUID? = null
        }

        /** The companies could not be read or stored; nothing was created. */
        data object Unavailable : Match() {
            override val id: UUID? = null
        }
    }
}
