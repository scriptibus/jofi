// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.CreateProviderPort
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderInput
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ProviderSettings
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import java.time.Clock
import java.util.UUID

/**
 * Adds an AI provider (spec §3.2). Its key goes only into the encrypted secret store; the provider
 * keeps the secret id. Key, provider and changelog entry are stored in one transaction.
 */
class CreateProviderUseCase(
    private val providers: ProviderConfigPort,
    private val secrets: SecretStorePort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : CreateProviderPort {
    override fun execute(
        kind: ProviderKind,
        input: ProviderInput,
        actor: Actor,
    ): SetupResult<ProviderConfig> =
        asUser(actor) {
            input.validate(kind, keyStored = false).toSetupResult().then { create(kind, it, actor) }
        }

    private fun create(
        kind: ProviderKind,
        settings: ProviderSettings,
        actor: Actor,
    ): SetupResult<ProviderConfig> {
        val key = settings.apiKey
        val keyId = key?.let { SecretId(UUID.randomUUID()) }
        val config = ProviderConfig(ProviderId(UUID.randomUUID()), settings.displayName, kind, keyId, settings.baseUri)
        val entity = config.id.toEntityRef()
        return transactions.whenSuccessful {
            when {
                keyId != null && secrets.put(keyId, key) !is SecretResult.Success -> {
                    SetupResult.StorageFailure("store key")
                }

                providers.save(config) !is SetupStoreResult.Success -> {
                    SetupResult.StorageFailure("save provider")
                }

                !changelog.record(
                    entity,
                    actor,
                    clock.storedNow(),
                    "Added AI provider",
                    providerChanges(null, config),
                ) -> {
                    SetupResult.StorageFailure("changelog")
                }

                else -> {
                    SetupResult.Success(config)
                }
            }
        }
    }
}
