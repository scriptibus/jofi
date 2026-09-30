// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.SearchApplicationsPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch

/** A page of the application list with its filters and order (#83). Listing does not mark anything read. */
class SearchApplicationsUseCase(
    private val applications: ApplicationRepositoryPort,
) : SearchApplicationsPort {
    override fun execute(search: ApplicationSearch): ApplicationResult<ApplicationPage<Application>> =
        applications.search(search).toResult()
}
