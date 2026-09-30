// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port

import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult

/**
 * Stores AI provider configs (table `ai_provider_config`; implemented with the use cases in #23).
 * The use case that calls [save] or [delete] also appends a changelog entry with the acting actor.
 * Implementations never throw.
 */
interface ProviderConfigPort {
    fun findAll(): SetupStoreResult<List<ProviderConfig>>

    fun findById(id: ProviderId): SetupStoreResult<ProviderConfig>

    /** Inserts or replaces the config with the same id. */
    fun save(config: ProviderConfig): SetupStoreResult<Unit>

    /** [SetupStoreResult.InUse] while a task is still assigned to the provider. */
    fun delete(id: ProviderId): SetupStoreResult<Unit>
}
