// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.UpdateProviderPort
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderInput
import io.github.scriptibus.jofi.setup.domain.ProviderSettings
import io.github.scriptibus.jofi.setup.domain.SetupField
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupViolation
import io.github.scriptibus.jofi.setup.domain.SetupViolationKind
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
            // Read and write in one transaction; the update-only store refuses a provider deleted meanwhile.
            transactions.whenSuccessful {
                providers.findById(id).toSetupResult().then { current ->
                    input
                        .validate(current.kind, keyStored = current.apiKey != null)
                        .toSetupResult()
                        .then { keyFollowsOrigin(current, it) }
                        .then { update(current, it, actor) }
                }
            }
        }

    private fun keyFollowsOrigin(
        current: ProviderConfig,
        settings: ProviderSettings,
    ): SetupResult<ProviderSettings> =
        if (settings.apiKey == null && current.keyMustBeReenteredFor(settings.baseUri)) {
            SetupResult.Invalid(listOf(SetupViolation(SetupField.API_KEY, SetupViolationKind.REQUIRED)))
        } else {
            SetupResult.Success(settings)
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
        return store(current, updated, key, actor)
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
        val keyStored =
            if (keyId == null || key == null || secrets.put(keyId, key) is SecretResult.Success) {
                SetupResult.Success(Unit)
            } else {
                SetupResult.StorageFailure("store key")
            }
        return keyStored.then { providers.update(updated).toSetupResult() }.then {
            val recorded = changelog.record(updated.id.toEntityRef(), actor, clock.storedNow(), description, changes)
            if (recorded) SetupResult.Success(updated) else SetupResult.StorageFailure("changelog")
        }
    }
}
