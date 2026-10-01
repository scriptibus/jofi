// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.DashboardRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ListRecentActivityPort
import io.github.scriptibus.jofi.applications.domain.ActivityEntry
import io.github.scriptibus.jofi.applications.domain.ActivityQuery
import io.github.scriptibus.jofi.applications.domain.ApplicationResult

/**
 * The dashboard's recent activity (spec §10.1, ADR-0052): the newest changelog entries of the job search, newest
 * first, with their actor and the application they are about. Reads only.
 */
class ListRecentActivityUseCase(
    private val dashboard: DashboardRepositoryPort,
) : ListRecentActivityPort {
    override fun execute(query: ActivityQuery): ApplicationResult<List<ActivityEntry>> =
        dashboard.recentActivity(query).toResult()
}
