// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.GetCompanyPort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanyView

/** One company with the number of its applications. */
class GetCompanyUseCase(
    private val companies: CompanyRepositoryPort,
    private val applications: ApplicationCountsPort,
) : GetCompanyPort {
    override fun execute(id: CompanyId): CompanyResult<CompanyView> =
        companies.findById(id).toResult().then(applications::viewOf)
}
