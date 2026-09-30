// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import java.net.URI

/**
 * Where each provider's API lives. Cloud providers have fixed endpoints (a provider config carries
 * no base URL for them); an OpenAI-compatible endpoint uses its configured base URL. Gemini and
 * Mistral are reached through their OpenAI-compatible APIs with the OpenAI client (ADR-0040).
 * Tests replace the cloud endpoints with WireMock.
 */
class ProviderEndpoints(
    private val cloud: Map<ProviderKind, URI> = DEFAULTS,
) {
    fun of(config: ProviderConfig): URI = config.baseUri ?: cloud.getValue(config.kind)

    companion object {
        val DEFAULTS: Map<ProviderKind, URI> =
            mapOf(
                ProviderKind.ANTHROPIC to URI("https://api.anthropic.com"),
                ProviderKind.OPENAI to URI("https://api.openai.com/v1"),
                ProviderKind.GEMINI to URI("https://generativelanguage.googleapis.com/v1beta/openai"),
                ProviderKind.MISTRAL to URI("https://api.mistral.ai/v1"),
            )
    }
}
