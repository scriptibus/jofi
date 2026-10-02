// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/** Whole texts with the "never send to AI" values taken out (ADR-0056), or why that was not possible. */
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
