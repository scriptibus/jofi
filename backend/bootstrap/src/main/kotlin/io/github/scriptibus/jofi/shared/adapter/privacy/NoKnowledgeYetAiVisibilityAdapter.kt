// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.privacy

import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import org.springframework.stereotype.Component

/**
 * The "never send to AI" source until the knowledge store exists (M2, ADR-0043): no item can be
 * flagged yet, so there are no flagged values, and no stored item is known, so a request quoting
 * one gets no verdict and is refused (fail closed). The knowledge context replaces this adapter with
 * one that answers from its entries; delete this class then, so exactly one source stays wired.
 */
@Component
class NoKnowledgeYetAiVisibilityAdapter : AiVisibilityPort {
    override fun rulesFor(sources: Set<ContentSource>): AiVisibilityResult =
        AiVisibilityResult.Known(NeverSendRules.NONE)
}
