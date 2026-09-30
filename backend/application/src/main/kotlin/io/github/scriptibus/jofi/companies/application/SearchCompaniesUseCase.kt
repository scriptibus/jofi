// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.SearchCompaniesPort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanyView

/** A page of companies (fuzzy name search, preference filter), with their application counts. */
class SearchCompaniesUseCase(
    private val companies: CompanyRepositoryPort,
    private val applications: ApplicationCountsPort,
) : SearchCompaniesPort {
    override fun execute(search: CompanySearch): CompanyResult<CompanyPage<CompanyView>> =
        companies.search(search).toResult().then { page ->
            applications.viewsOf(page.items).then { CompanyResult.Success(CompanyPage(it, page.total)) }
        }
}
