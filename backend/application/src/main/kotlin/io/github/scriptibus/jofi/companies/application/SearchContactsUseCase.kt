// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.SearchContactsPort
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactSearch

/** A page of contacts (fuzzy name search, company filter), with their channels. */
class SearchContactsUseCase(
    private val contacts: ContactRepositoryPort,
) : SearchContactsPort {
    override fun execute(search: ContactSearch): ContactResult<CompanyPage<Contact>> =
        contacts.search(search).toResult()
}
