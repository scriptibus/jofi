// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.config

import io.github.scriptibus.jofi.applications.application.CreateSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.DeleteSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.GetSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.ListSavedViewsUseCase
import io.github.scriptibus.jofi.applications.application.UpdateSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.config.ApplicationsConfiguration.ApplicationAudit
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The saved views of the application list (#99, ADR-0050). */
@Configuration(proxyBeanMethods = false)
class SavedViewConfiguration {
    @Bean
    fun createSavedViewUseCase(
        views: SavedViewRepositoryPort,
        audit: ApplicationAudit,
    ): CreateSavedViewUseCase = CreateSavedViewUseCase(views, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun updateSavedViewUseCase(
        views: SavedViewRepositoryPort,
        audit: ApplicationAudit,
    ): UpdateSavedViewUseCase = UpdateSavedViewUseCase(views, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun getSavedViewUseCase(views: SavedViewRepositoryPort): GetSavedViewUseCase = GetSavedViewUseCase(views)

    @Bean
    fun listSavedViewsUseCase(views: SavedViewRepositoryPort): ListSavedViewsUseCase = ListSavedViewsUseCase(views)

    @Bean
    fun deleteSavedViewUseCase(
        views: SavedViewRepositoryPort,
        confirmation: ConfirmActionUseCase,
        audit: ApplicationAudit,
    ): DeleteSavedViewUseCase =
        DeleteSavedViewUseCase(views, confirmation, audit.changelog, audit.transactions, audit.clock)
}
