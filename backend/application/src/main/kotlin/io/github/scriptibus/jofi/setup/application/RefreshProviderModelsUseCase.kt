// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.RefreshProviderModelsPort
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import java.time.Clock

/**
 * The connection test: lists the provider's models through the guarded AI transport (ADR-0040) and
 * stores them as DETECTED capability profiles. Profiles the user corrected are kept, and models the
 * provider no longer lists stay until the provider is removed. The provider call runs outside the
 * transaction; a failed call stores nothing.
 */
class RefreshProviderModelsUseCase(
    private val providers: ProviderConfigPort,
    private val catalog: ModelCatalogPort,
    private val profiles: ModelCapabilityPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : RefreshProviderModelsPort {
    override fun execute(
        id: ProviderId,
        actor: Actor,
    ): SetupResult<List<ModelCapabilityProfile>> =
        asUser(actor) {
            providers.findById(id).toSetupResult().then { provider ->
                when (val listed = catalog.detect(provider)) {
                    is AiResult.Success -> transactions.whenSuccessful { store(provider, listed.value, actor) }
                    else -> SetupResult.ProviderFailed(listed)
                }
            }
        }

    private fun store(
        provider: ProviderConfig,
        detected: List<ModelCapabilityProfile>,
        actor: Actor,
    ): SetupResult<List<ModelCapabilityProfile>> =
        profiles.findByProvider(provider.id).toSetupResult().then { stored ->
            val corrected = stored.filter { it.source == CapabilitySource.USER }.map { it.model }.toSet()
            val now = clock.storedNow()
            val fresh = detected.filterNot { it.model in corrected }.map { it.copy(updatedAt = now) }
            val description = "Refreshed the model list: ${detected.size} models listed, ${fresh.size} updated"
            when {
                fresh.any { profiles.save(it) !is SetupStoreResult.Success } -> {
                    SetupResult.StorageFailure("save models")
                }

                !changelog.record(provider.id.toEntityRef(), actor, now, description) -> {
                    SetupResult.StorageFailure("changelog")
                }

                else -> {
                    profiles.findByProvider(provider.id).toSetupResult()
                }
            }
        }
}
