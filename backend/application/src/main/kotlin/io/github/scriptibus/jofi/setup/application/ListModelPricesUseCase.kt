// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelPricePort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.ListModelPricesPort
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupResult

/** The prices the user gave the models of a provider (empty for a cloud provider), by model name. */
class ListModelPricesUseCase(
    private val providers: ProviderConfigPort,
    private val prices: ModelPricePort,
) : ListModelPricesPort {
    override fun execute(id: ProviderId): SetupResult<List<ModelPriceOverride>> =
        providers.findById(id).toSetupResult().then { prices.findByProvider(id).toSetupResult() }
}
