// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port

import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult

/**
 * Stores what each provider's models can do (table `ai_model_capability`, one row per provider and
 * model; filled by #19, corrected by the user in #23). Callers of [save] append a changelog entry.
 * Never throws.
 */
interface ModelCapabilityPort {
    fun find(
        provider: ProviderId,
        model: ModelName,
    ): SetupStoreResult<ModelCapabilityProfile>

    fun findByProvider(provider: ProviderId): SetupStoreResult<List<ModelCapabilityProfile>>

    /** Inserts or replaces the profile of the same provider and model. */
    fun save(profile: ModelCapabilityProfile): SetupStoreResult<Unit>
}
