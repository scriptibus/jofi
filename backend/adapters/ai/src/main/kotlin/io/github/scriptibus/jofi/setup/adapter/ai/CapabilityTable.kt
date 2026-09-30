// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderKind

/**
 * What the model families of each provider can do (spec §3.2 capability checks), from the
 * providers' model documentation as of 2026-09. Provider APIs do not report tool use or streaming
 * in a common way, so the table is the source; the user corrects it in the setup (#23). The first
 * matching family wins; an unknown model (and every model of an OpenAI-compatible endpoint, which
 * can serve anything) has no known capabilities.
 */
internal object CapabilityTable {
    private class Family(
        val kind: ProviderKind,
        pattern: String,
        val capabilities: Set<Capability>,
    ) {
        val pattern = Regex(pattern)
    }

    private val CHAT = setOf(Capability.ToolUse, Capability.Streaming)

    private fun context(tokens: Int) = Capability.ContextSize(tokens)

    private val FAMILIES =
        listOf(
            Family(ProviderKind.ANTHROPIC, "claude-.*", CHAT + context(tokens = 200_000)),
            Family(ProviderKind.OPENAI, "text-embedding-.*", setOf(Capability.Embedding, context(tokens = 8_191))),
            Family(
                ProviderKind.OPENAI,
                "gpt-4o(-mini)?-transcribe.*",
                setOf(Capability.SpeechToText, Capability.Streaming),
            ),
            Family(ProviderKind.OPENAI, "whisper-.*", setOf(Capability.SpeechToText)),
            Family(
                ProviderKind.OPENAI,
                "(tts-|gpt-4o-mini-tts).*",
                setOf(Capability.TextToSpeech, Capability.Streaming),
            ),
            Family(ProviderKind.OPENAI, "gpt-4\\.1.*", CHAT + context(tokens = 1_047_576)),
            Family(ProviderKind.OPENAI, "gpt-5.*", CHAT + context(tokens = 400_000)),
            Family(ProviderKind.OPENAI, "o[1-9](-.*)?", CHAT + context(tokens = 200_000)),
            Family(ProviderKind.OPENAI, "gpt-4o.*", CHAT + context(tokens = 128_000)),
            Family(
                ProviderKind.GEMINI,
                "(gemini-embedding|text-embedding|embedding-).*",
                setOf(Capability.Embedding, context(tokens = 2_048)),
            ),
            Family(ProviderKind.GEMINI, "gemini-.*-tts.*", setOf(Capability.TextToSpeech, Capability.Streaming)),
            Family(ProviderKind.GEMINI, "gemini-.*", CHAT + context(tokens = 1_048_576)),
            Family(
                ProviderKind.MISTRAL,
                "(mistral|codestral)-embed.*",
                setOf(Capability.Embedding, context(tokens = 8_192)),
            ),
            Family(ProviderKind.MISTRAL, "voxtral-.*", setOf(Capability.SpeechToText)),
            Family(
                ProviderKind.MISTRAL,
                "(mistral-(large|medium|small)|magistral|ministral|codestral|devstral|pixtral|open-mistral-nemo).*",
                CHAT + context(tokens = 128_000),
            ),
        )

    fun of(
        kind: ProviderKind,
        model: ModelName,
    ): ModelCapabilities {
        val name = normalize(model.value)
        return FAMILIES
            .firstOrNull { it.kind == kind && it.pattern.matches(name) }
            ?.let { ModelCapabilities(it.capabilities) }
            ?: ModelCapabilities.NONE
    }

    /** Gemini lists `models/gemini-...`; names are matched case-insensitively. */
    fun normalize(model: String): String = model.removePrefix("models/").lowercase()
}
