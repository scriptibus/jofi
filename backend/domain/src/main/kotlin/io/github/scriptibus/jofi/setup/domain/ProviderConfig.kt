// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import java.net.URI
import java.util.UUID

/** Identifies one configured AI provider. */
@JvmInline
value class ProviderId(
    val value: UUID,
) {
    /** How changelog entries refer to this provider (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of AI providers (spec §13); never rename it, stored entries use it. */
        const val ENTITY_TYPE = "ai_provider"

        /** The confirmation operation of removing a provider (ADR-0039). */
        const val DELETE_OPERATION = "setup.delete-provider"
    }
}

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
        // Credentials belong in the secret store: a key in the URL would be stored, listed and
        // exported in clear text (ADR-0017, threat model T4).
        require(baseUri == null || isFreeOfCredentials(baseUri)) {
            "A base URL must not contain user info, a query or a fragment"
        }
    }

    /**
     * Whether moving to [newBaseUri] must come with the key again: a stored key belongs to the origin
     * (scheme, host, port) it was entered for, so a changed origin, including an https to http downgrade
     * on the same host, would otherwise send it to a server the user never gave it to.
     */
    fun keyMustBeReenteredFor(newBaseUri: URI?): Boolean = apiKey != null && originOf(baseUri) != originOf(newBaseUri)

    companion object {
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443

        private fun originOf(uri: URI?): Triple<String, String, Int>? =
            uri?.let {
                val scheme = it.scheme.lowercase()
                val port =
                    if (it.port != -1) {
                        it.port
                    } else if (scheme == "https") {
                        HTTPS_PORT
                    } else {
                        HTTP_PORT
                    }
                Triple(scheme, it.host.lowercase(), port)
            }

        /** An absolute http(s) URL with a host and without user info, query or fragment. */
        fun isValidBaseUri(uri: URI): Boolean = isHttpUrl(uri) && isFreeOfCredentials(uri)

        private fun isHttpUrl(uri: URI): Boolean =
            uri.isAbsolute && uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()

        private fun isFreeOfCredentials(uri: URI): Boolean =
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }
}
