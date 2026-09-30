// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port

import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.ai.AiResult

/**
 * What a provider's models can do, for the setup capability checks (spec §3.2; implemented in
 * `setup.adapter.ai`, #19). Capabilities come from the adapter's table of known model families; the
 * provider itself only says which models exist. The setup use cases (#23) store the detected
 * profiles through [ModelCapabilityPort] without overwriting the user's corrections. Never throws.
 */
interface ModelCatalogPort {
    /**
     * Lists the models [provider] offers, each with its known capabilities, as
     * [io.github.scriptibus.jofi.setup.domain.CapabilitySource.DETECTED] profiles. Calls the
     * provider (its API key is read through `SecretStorePort`); failures are [AiResult] variants.
     */
    fun detect(provider: ProviderConfig): AiResult<List<ModelCapabilityProfile>>

    /**
     * The known capabilities of [model] for a provider of [kind], without calling the provider (for
     * model names the user types in). [ModelCapabilities.NONE] for an unknown model.
     */
    fun knownCapabilities(
        kind: ProviderKind,
        model: ModelName,
    ): ModelCapabilities
}
