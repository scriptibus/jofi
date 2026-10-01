// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.config

import io.github.scriptibus.jofi.applications.application.GetPipelineOverviewUseCase
import io.github.scriptibus.jofi.applications.application.ListRecentActivityUseCase
import io.github.scriptibus.jofi.applications.application.port.DashboardRepositoryPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The dashboard's application figures (#113, ADR-0052): the pipeline overview and the recent activity. */
@Configuration(proxyBeanMethods = false)
class DashboardConfiguration {
    @Bean
    fun getPipelineOverviewUseCase(dashboard: DashboardRepositoryPort): GetPipelineOverviewUseCase =
        GetPipelineOverviewUseCase(dashboard)

    @Bean
    fun listRecentActivityUseCase(dashboard: DashboardRepositoryPort): ListRecentActivityUseCase =
        ListRecentActivityUseCase(dashboard)
}
