// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

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
