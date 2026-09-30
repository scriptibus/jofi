// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import java.time.Instant

/** Something a model can do that a task may depend on (spec §3.2 capability checks). */
sealed interface Capability {
    /** Reliable tool (function) calling. */
    data object ToolUse : Capability

    /** Token streaming (or streaming audio for the voice capabilities). */
    data object Streaming : Capability

    data object SpeechToText : Capability

    data object TextToSpeech : Capability

    /** Produces embedding vectors. */
    data object Embedding : Capability

    /** A context window of at least [tokens] tokens. */
    data class ContextSize(
        val tokens: Int,
    ) : Capability {
        init {
            require(tokens > 0) { "A context size must be positive" }
        }
    }
}

/** What one model offers: feature capabilities plus at most one [Capability.ContextSize]. */
data class ModelCapabilities(
    val supported: Set<Capability>,
) {
    init {
        require(supported.count { it is Capability.ContextSize } <= 1) { "A model has one context size" }
    }

    val contextSize: Capability.ContextSize? get() = supported.filterIsInstance<Capability.ContextSize>().firstOrNull()

    /** True when this model meets [required]; a context size is met by any window at least as large. */
    fun meets(required: Capability): Boolean =
        when (required) {
            is Capability.ContextSize -> (contextSize?.tokens ?: 0) >= required.tokens
            else -> required in supported
        }

    companion object {
        val NONE = ModelCapabilities(emptySet())
    }
}

/**
 * Stable storage names of the feature capabilities (the `ai_model_capability.capabilities` array).
 * The context size is stored in its own column, so it has no name here. Renaming needs a migration.
 */
enum class CapabilityName(
    val capability: Capability,
) {
    TOOL_USE(Capability.ToolUse),
    STREAMING(Capability.Streaming),
    SPEECH_TO_TEXT(Capability.SpeechToText),
    TEXT_TO_SPEECH(Capability.TextToSpeech),
    EMBEDDING(Capability.Embedding),
    ;

    companion object {
        /** The storage name of [capability], or null for [Capability.ContextSize]. */
        fun of(capability: Capability): CapabilityName? = entries.firstOrNull { it.capability == capability }
    }
}

/** Where a model's capabilities came from. */
enum class CapabilitySource {
    /** Entered or corrected by the user. */
    USER,

    /** Taken from the AI adapter's capability table or the provider's model listing (#19). */
    DETECTED,
}

/**
 * What [model] of [provider] can do. Capabilities belong to the provider and model, not to a task
 * assignment: several tasks can share one model, and re-assigning a task does not lose them.
 */
data class ModelCapabilityProfile(
    val provider: ProviderId,
    val model: ModelName,
    val capabilities: ModelCapabilities,
    val source: CapabilitySource,
    val updatedAt: Instant,
) {
    /** The warnings for running [task] on this model, see [CapabilityCheck]. */
    fun warningsFor(task: AiTask): List<CapabilityWarning> = CapabilityCheck.warningsFor(task, capabilities)
}
