// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.api.MatchCompanyPort
import io.github.scriptibus.jofi.companies.application.port.api.MatchCompanyPort.Match
import io.github.scriptibus.jofi.companies.application.port.inbound.CreateCompanyPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyNameKey
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.shared.domain.Actor

/**
 * Finds the company a posting names or creates it (#96). The fuzzy name search of the company list, for the name
 * without its legal form, narrows the candidates (best match first); only a candidate with the same [CompanyNameKey]
 * counts (an exact name first, ambiguity creates), so a merely similar name creates a new company. A name without
 * letters or digits is no name. Creating goes through [CreateCompanyPort], with its validation and changelog entry.
 */
class MatchCompanyUseCase(
    private val companies: CompanyRepositoryPort,
    private val create: CreateCompanyPort,
) : MatchCompanyPort {
    override fun execute(
        name: String,
        actor: Actor,
    ): Match {
        val text = name.trim()
        val search = CompanyNameKey.searchText(text)
        val candidates =
            if (search.isEmpty()) {
                null
            } else {
                companies.search(
                    CompanySearch(text = search, size = CANDIDATES),
                )
            }
        return when (candidates) {
            null -> {
                Match.InvalidName
            }

            is CompanyStoreResult.Success -> {
                matchOf(text, candidates.value.items)?.let { Match.Found(it.id.value) } ?: create(text, actor)
            }

            else -> {
                Match.Unavailable
            }
        }
    }

    /**
     * The candidate with exactly this name (NFC, case folded), else the only one with the same key. Several with the
     * same key and none exact ("Foo AG" and "Foo GmbH" for "Foo SE") is ambiguous: no match.
     */
    private fun matchOf(
        name: String,
        candidates: List<Company>,
    ): Company? {
        val key = CompanyNameKey.of(name)
        val sameKey = candidates.filter { CompanyNameKey.of(it.details.name) == key }
        val folded = CompanyNameKey.folded(name)
        return sameKey.firstOrNull { CompanyNameKey.folded(it.details.name) == folded } ?: sameKey.singleOrNull()
    }

    private fun create(
        name: String,
        actor: Actor,
    ): Match =
        when (val created = create.execute(CompanyInput(name), actor)) {
            is CompanyResult.Success -> Match.Created(created.value.company.id.value)
            is CompanyResult.Invalid -> Match.InvalidName
            else -> Match.Unavailable
        }

    private companion object {
        /** Enough for every company whose name contains the text; the exact key sits near the top. */
        const val CANDIDATES = 20
    }
}
