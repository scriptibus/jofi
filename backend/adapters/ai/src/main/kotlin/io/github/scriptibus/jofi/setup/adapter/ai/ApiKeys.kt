// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue

/**
 * Reads a provider's API key through the secret store just before the call, so a changed key
 * applies at once and no key is cached here. A missing or undecryptable key is an authentication
 * failure (the provider would reject the call anyway); a failing store is temporary.
 */
internal class ApiKeys(
    private val secrets: SecretStorePort,
) {
    sealed interface Lookup {
        /** [key] is null for a provider without one (a local OpenAI-compatible endpoint). */
        class Found(
            val key: SecretValue?,
        ) : Lookup

        class Failed(
            val result: AiResult<Nothing>,
        ) : Lookup
    }

    fun of(provider: ProviderConfig): Lookup {
        val id = provider.apiKey ?: return Lookup.Found(null)
        return when (val stored = secrets.get(id)) {
            is SecretResult.Success -> Lookup.Found(stored.value)
            SecretResult.NotFound, SecretResult.Undecryptable -> Lookup.Failed(AiResult.AuthenticationFailed)
            is SecretResult.StorageFailure -> Lookup.Failed(AiResult.Unavailable)
        }
    }
}
