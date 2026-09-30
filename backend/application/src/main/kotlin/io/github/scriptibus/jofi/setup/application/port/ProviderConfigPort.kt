// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port

import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult

/**
 * Stores AI provider configs (table `ai_provider_config`).
 * The use case that calls [save] or [delete] also appends a changelog entry with the acting actor.
 * Implementations never throw.
 */
interface ProviderConfigPort {
    fun findAll(): SetupStoreResult<List<ProviderConfig>>

    fun findById(id: ProviderId): SetupStoreResult<ProviderConfig>

    /** Inserts or replaces the config with the same id. */
    fun save(config: ProviderConfig): SetupStoreResult<Unit>

    /**
     * Replaces the stored config with the same id, never inserts: [SetupStoreResult.NotFound] when it
     * is gone, so an update racing a delete cannot bring the provider back.
     */
    fun update(config: ProviderConfig): SetupStoreResult<Unit>

    /**
     * Removes the provider and its model capabilities, only with a [proof] covering
     * [ProviderId.DELETE_OPERATION] for [id] (else [SetupStoreResult.NotConfirmed], ADR-0039).
     * [SetupStoreResult.InUse] while a task is still assigned to the provider.
     */
    fun delete(
        id: ProviderId,
        proof: ConfirmationResult.Confirmed,
    ): SetupStoreResult<Unit>
}
