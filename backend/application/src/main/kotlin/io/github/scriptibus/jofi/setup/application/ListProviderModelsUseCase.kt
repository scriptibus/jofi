// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.ListProviderModelsPort
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult

/** The stored capability profiles of a provider's models (detected or corrected), by model name. */
class ListProviderModelsUseCase(
    private val providers: ProviderConfigPort,
    private val profiles: ModelCapabilityPort,
) : ListProviderModelsPort {
    override fun execute(id: ProviderId): SetupResult<List<ModelCapabilityProfile>> =
        when (val found = providers.findById(id)) {
            is SetupStoreResult.Success -> profiles.findByProvider(id).toSetupResult()
            else -> found.asFailure()
        }
}
