// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.config

import io.github.scriptibus.jofi.setup.application.AssignTaskModelUseCase
import io.github.scriptibus.jofi.setup.application.CorrectModelCapabilitiesUseCase
import io.github.scriptibus.jofi.setup.application.CreateProviderUseCase
import io.github.scriptibus.jofi.setup.application.DeleteProviderUseCase
import io.github.scriptibus.jofi.setup.application.ListProviderModelsUseCase
import io.github.scriptibus.jofi.setup.application.ListProvidersUseCase
import io.github.scriptibus.jofi.setup.application.ListTaskAssignmentsUseCase
import io.github.scriptibus.jofi.setup.application.RefreshProviderModelsUseCase
import io.github.scriptibus.jofi.setup.application.UpdateProviderUseCase
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The provider setup use cases (#23): providers, their models and the per-task assignments. */
@Configuration(proxyBeanMethods = false)
class SetupConfiguration {
    @Bean
    fun listProvidersUseCase(providers: ProviderConfigPort): ListProvidersUseCase = ListProvidersUseCase(providers)

    @Bean
    fun createProviderUseCase(
        providers: ProviderConfigPort,
        secrets: SecretStorePort,
        audit: SetupAudit,
    ): CreateProviderUseCase =
        CreateProviderUseCase(providers, secrets, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun updateProviderUseCase(
        providers: ProviderConfigPort,
        secrets: SecretStorePort,
        audit: SetupAudit,
    ): UpdateProviderUseCase =
        UpdateProviderUseCase(providers, secrets, audit.changelog, audit.transactions, audit.clock)

    @Bean
    fun deleteProviderUseCase(
        stores: SetupStores,
        secrets: SecretStorePort,
        confirmation: ConfirmActionUseCase,
        audit: SetupAudit,
    ): DeleteProviderUseCase =
        DeleteProviderUseCase(
            stores.providers,
            stores.assignments,
            secrets,
            confirmation,
            audit.changelog,
            audit.transactions,
            audit.clock,
        )

    @Bean
    fun refreshProviderModelsUseCase(
        stores: SetupStores,
        catalog: ModelCatalogPort,
        audit: SetupAudit,
    ): RefreshProviderModelsUseCase =
        RefreshProviderModelsUseCase(
            stores.providers,
            catalog,
            stores.profiles,
            audit.changelog,
            audit.transactions,
            audit.clock,
        )

    @Bean
    fun listProviderModelsUseCase(stores: SetupStores): ListProviderModelsUseCase =
        ListProviderModelsUseCase(stores.providers, stores.profiles)

    @Bean
    fun correctModelCapabilitiesUseCase(
        stores: SetupStores,
        audit: SetupAudit,
    ): CorrectModelCapabilitiesUseCase =
        CorrectModelCapabilitiesUseCase(
            stores.providers,
            stores.profiles,
            audit.changelog,
            audit.transactions,
            audit.clock,
        )

    @Bean
    fun listTaskAssignmentsUseCase(
        stores: SetupStores,
        catalog: ModelCatalogPort,
    ): ListTaskAssignmentsUseCase =
        ListTaskAssignmentsUseCase(stores.assignments, stores.providers, stores.profiles, catalog)

    @Bean
    fun assignTaskModelUseCase(
        stores: SetupStores,
        catalog: ModelCatalogPort,
        audit: SetupAudit,
    ): AssignTaskModelUseCase =
        AssignTaskModelUseCase(
            stores.providers,
            stores.assignments,
            stores.profiles,
            catalog,
            audit.changelog,
            audit.transactions,
            audit.clock,
        )

    @Bean
    fun setupStores(
        providers: ProviderConfigPort,
        assignments: ModelAssignmentPort,
        profiles: ModelCapabilityPort,
    ): SetupStores = SetupStores(providers, assignments, profiles)

    @Bean
    fun setupAudit(
        changelog: ChangelogPort,
        transactions: TransactionPort,
        clock: Clock,
    ): SetupAudit = SetupAudit(changelog, transactions, clock)

    /** The setup repositories, grouped to keep the bean methods short. */
    class SetupStores(
        val providers: ProviderConfigPort,
        val assignments: ModelAssignmentPort,
        val profiles: ModelCapabilityPort,
    )

    /** What every setup mutation writes with: the changelog, in one transaction, at the clock's time. */
    class SetupAudit(
        val changelog: ChangelogPort,
        val transactions: TransactionPort,
        val clock: Clock,
    )
}
