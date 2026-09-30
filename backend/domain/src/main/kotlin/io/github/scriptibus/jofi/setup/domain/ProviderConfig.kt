// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import java.net.URI
import java.util.UUID

/** Identifies one configured AI provider. */
@JvmInline
value class ProviderId(
    val value: UUID,
)

/**
 * The supported AI providers (spec §3.2). Cloud providers have fixed endpoints and need a key; an
 * OpenAI-compatible endpoint (Ollama, LM Studio, vLLM, OpenRouter, ...) needs a base URL and may
 * work without a key.
 */
enum class ProviderKind(
    val needsBaseUri: Boolean,
    val needsApiKey: Boolean,
) {
    ANTHROPIC(needsBaseUri = false, needsApiKey = true),
    OPENAI(needsBaseUri = false, needsApiKey = true),
    GEMINI(needsBaseUri = false, needsApiKey = true),
    MISTRAL(needsBaseUri = false, needsApiKey = true),
    OPENAI_COMPATIBLE(needsBaseUri = true, needsApiKey = false),
}

/**
 * A configured AI provider. It references its API key by [apiKey] in the secret store and never
 * holds the key itself (ADR-0017), so a provider config can be listed, logged or exported safely.
 */
data class ProviderConfig(
    val id: ProviderId,
    val displayName: String,
    val kind: ProviderKind,
    val apiKey: SecretId?,
    val baseUri: URI? = null,
) {
    init {
        require(displayName.isNotBlank()) { "A provider needs a display name" }
        require(!kind.needsApiKey || apiKey != null) { "$kind needs an API key" }
        require(kind.needsBaseUri == (baseUri != null)) {
            if (kind.needsBaseUri) "$kind needs a base URL" else "$kind has a fixed endpoint and takes no base URL"
        }
        require(baseUri == null || isHttpUrl(baseUri)) { "A base URL must be an absolute http(s) URL" }
    }

    private companion object {
        fun isHttpUrl(uri: URI): Boolean =
            uri.isAbsolute && uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }
}
