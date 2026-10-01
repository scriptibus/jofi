// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

/** What the "never send to AI" filter made of an MCP tool result (#116, ADR-0053). */
sealed interface FilteredToolResult {
    /** [json] may leave Jofi; [redactions] pieces were replaced by [NeverSendFilter.REDACTION]. */
    data class Passed(
        val json: String,
        val redactions: Int,
    ) : FilteredToolResult {
        override fun toString(): String = "Passed(chars=${json.length}, redactions=$redactions)"
    }

    /** The flags could not be read; nothing may leave (fail closed). Carries no content. */
    data object Refused : FilteredToolResult
}
