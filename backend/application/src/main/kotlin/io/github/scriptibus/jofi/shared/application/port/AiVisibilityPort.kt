// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource

/**
 * The source of the "never send to AI" flags (spec §4.1, ADR-0043), asked by the AI gateway before
 * every provider call and by the MCP result filter (#116). The knowledge context implements it
 * when flagged entries exist (M2).
 *
 * Contract, which the gateway relies on to fail closed:
 * - The answer holds a verdict for every one of [sources] the implementation knows. A source it
 *   does not know gets no verdict, and the gateway refuses the call.
 * - The answer holds every flagged value of every flagged item, not only of [sources]: text
 *   without a source is scanned for them too.
 * - When it cannot answer completely, it returns [AiVisibilityResult.Unavailable]. It never throws
 *   and never answers from a partial read.
 */
interface AiVisibilityPort {
    fun rulesFor(sources: Set<ContentSource>): AiVisibilityResult
}
