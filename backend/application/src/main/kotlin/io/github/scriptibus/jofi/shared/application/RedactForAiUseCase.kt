// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application

import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter

/**
 * The "never send to AI" values taken out of whole texts, before a list cuts excerpts from them (ADR-0056). The MCP
 * result filter (ADR-0053) still runs on every result; this is what keeps a cut from ending inside a flagged value,
 * which that filter would no longer recognise. The flags are read once for all [execute]d texts.
 */
class RedactForAiUseCase(
    private val visibility: AiVisibilityPort,
) {
    fun execute(texts: List<String?>): AiRedaction =
        when (val answer = visibility.rulesFor(emptySet())) {
            is AiVisibilityResult.Unavailable -> {
                AiRedaction.Unavailable
            }

            is AiVisibilityResult.Known -> {
                AiRedaction.Redacted(texts.map { text -> text?.let { NeverSendFilter.redactText(it, answer.rules) } })
            }
        }
}

sealed interface AiRedaction {
    /** The texts as they may reach an AI, in the order given. */
    data class Redacted(
        val texts: List<String?>,
    ) : AiRedaction {
        override fun toString(): String = "Redacted(texts=${texts.size})"
    }

    /** The flags could not be read; nothing may reach an AI (fail closed). */
    data object Unavailable : AiRedaction
}
