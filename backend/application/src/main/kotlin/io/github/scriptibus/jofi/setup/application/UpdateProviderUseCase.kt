// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.UpdateProviderPort
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderInput
import io.github.scriptibus.jofi.setup.domain.ProviderSettings
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import java.time.Clock
import java.util.UUID

/**
 * Changes a provider's name, base URL or key; the kind stays. A new base URL takes effect in the AI
 * transport's allowlist with the next connection (ADR-0034). A given key replaces the stored one under
 * the same secret id; without one the key stays. An unchanged provider writes nothing.
 */
class UpdateProviderUseCase(
    private val providers: ProviderConfigPort,
    private val secrets: SecretStorePort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : UpdateProviderPort {
    override fun execute(
        id: ProviderId,
        input: ProviderInput,
        actor: Actor,
    ): SetupResult<ProviderConfig> =
        asUser(actor) {
            providers.findById(id).toSetupResult().then { current ->
                input.validate(current.kind, keyStored = current.apiKey != null).toSetupResult().then {
                    update(current, it, actor)
                }
            }
        }

    private fun update(
        current: ProviderConfig,
        settings: ProviderSettings,
        actor: Actor,
    ): SetupResult<ProviderConfig> {
        val key = settings.apiKey
        val keyId = current.apiKey ?: key?.let { SecretId(UUID.randomUUID()) }
        val updated = current.copy(displayName = settings.displayName, baseUri = settings.baseUri, apiKey = keyId)
        if (updated == current && key == null) return SetupResult.Success(current)
        return transactions.whenSuccessful { store(current, updated, key, actor) }
    }

    private fun store(
        current: ProviderConfig,
        updated: ProviderConfig,
        key: SecretValue?,
        actor: Actor,
    ): SetupResult<ProviderConfig> {
        val keyId = updated.apiKey
        val replaced = key != null && current.apiKey != null
        val description = if (replaced) "Changed AI provider and replaced its API key" else "Changed AI provider"
        val changes = providerChanges(current, updated)
        return when {
            keyId != null && key != null && secrets.put(keyId, key) !is SecretResult.Success -> {
                SetupResult.StorageFailure("store key")
            }

            providers.save(updated) !is SetupStoreResult.Success -> {
                SetupResult.StorageFailure("save provider")
            }

            !changelog.record(updated.id.toEntityRef(), actor, clock.storedNow(), description, changes) -> {
                SetupResult.StorageFailure("changelog")
            }

            else -> {
                SetupResult.Success(updated)
            }
        }
    }
}
