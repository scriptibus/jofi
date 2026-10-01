// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port

import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult

/**
 * Stores the prices the user gives models of OpenAI-compatible providers (table
 * `ai_model_price_override`, one row per provider and model, deleted with the provider; ADR-0055). The AI
 * gateway reads it for every call it meters. Callers of [save] and [clear] append a changelog entry.
 * Never throws.
 */
interface ModelPricePort {
    fun find(
        provider: ProviderId,
        model: ModelName,
    ): SetupStoreResult<ModelPriceOverride>

    fun findByProvider(provider: ProviderId): SetupStoreResult<List<ModelPriceOverride>>

    /**
     * Inserts or replaces the price of the same provider and model; [SetupStoreResult.NotFound] if the
     * provider is gone.
     */
    fun save(price: ModelPriceOverride): SetupStoreResult<Unit>

    /** Removes the price of [model]; succeeds when there was none. */
    fun clear(
        provider: ProviderId,
        model: ModelName,
    ): SetupStoreResult<Unit>
}
