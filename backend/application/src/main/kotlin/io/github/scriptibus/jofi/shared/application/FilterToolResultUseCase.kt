// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application

import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.FilteredToolResult
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendFilter

/**
 * The "never send to AI" filter on MCP tool results (spec §9.1, threat model T3, ADR-0053): every MCP
 * client, the built-in chat included, is an AI, so no flagged value may leave in a tool result. The MCP
 * server runs it on every result; tools never call it. When the flags cannot be read, nothing leaves.
 */
class FilterToolResultUseCase(
    private val visibility: AiVisibilityPort,
) {
    fun execute(json: String): FilteredToolResult =
        when (val answer = visibility.rulesFor(emptySet())) {
            is AiVisibilityResult.Unavailable -> {
                FilteredToolResult.Refused
            }

            is AiVisibilityResult.Known -> {
                val passed = NeverSendFilter.applyToToolResult(json, answer.rules)
                FilteredToolResult.Passed(passed.value, passed.redactions)
            }
        }
}
