// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.DashboardRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.GetPipelineOverviewPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.PipelineOverview

/** The dashboard's pipeline figures (spec §10.1); the funnel's stages are defined in ADR-0052. Reads only. */
class GetPipelineOverviewUseCase(
    private val dashboard: DashboardRepositoryPort,
) : GetPipelineOverviewPort {
    override fun execute(): ApplicationResult<PipelineOverview> = dashboard.pipeline().toResult()
}
