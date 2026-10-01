// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.config

import io.github.scriptibus.jofi.applications.application.DescribeApplicationEventUseCase
import io.github.scriptibus.jofi.applications.application.FindGhostedCandidatesUseCase
import io.github.scriptibus.jofi.applications.application.FindSuggestionFactsUseCase
import io.github.scriptibus.jofi.applications.application.GetApplicationSettingsUseCase
import io.github.scriptibus.jofi.applications.application.UpdateApplicationSettingsUseCase
import io.github.scriptibus.jofi.applications.application.port.ApplicationActivityRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationSettingsRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.config.ApplicationsConfiguration.ApplicationAudit
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The application settings (#85, ADR-0050) and what the tasks context asks for through the named interface `api`: the
 * Ghosted candidates, the facts of its suggestions and the names of this context's events (#95).
 */
@Configuration(proxyBeanMethods = false)
class ApplicationSettingsConfiguration {
    @Bean
    fun getApplicationSettingsUseCase(settings: ApplicationSettingsRepositoryPort): GetApplicationSettingsUseCase =
        GetApplicationSettingsUseCase(settings)

    @Bean
    fun updateApplicationSettingsUseCase(
        settings: ApplicationSettingsRepositoryPort,
        audit: ApplicationAudit,
    ): UpdateApplicationSettingsUseCase =
        UpdateApplicationSettingsUseCase(settings, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun findGhostedCandidatesUseCase(
        settings: ApplicationSettingsRepositoryPort,
        activity: ApplicationActivityRepositoryPort,
    ): FindGhostedCandidatesUseCase = FindGhostedCandidatesUseCase(settings, activity)

    @Bean
    fun findSuggestionFactsUseCase(
        settings: ApplicationSettingsRepositoryPort,
        activity: ApplicationActivityRepositoryPort,
        interviews: InterviewRepositoryPort,
        applications: ApplicationRepositoryPort,
    ): FindSuggestionFactsUseCase = FindSuggestionFactsUseCase(settings, activity, interviews, applications)

    @Bean
    fun describeApplicationEventUseCase(): DescribeApplicationEventUseCase = DescribeApplicationEventUseCase()
}
