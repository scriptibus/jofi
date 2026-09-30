// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse

/**
 * Text generation with a language model (ADR-0011). The request's task decides provider and model;
 * routing, capability checks, cost metering and the "never send to AI" filter wrap this port in the
 * application layer (#20). Implementations never throw: failures come back as [AiResult] variants.
 * Calls block; they run on virtual threads.
 */
interface LlmPort {
    /** Sends [request] and returns the complete answer with its token usage. */
    fun complete(request: LlmRequest): AiResult<LlmResponse>

    /**
     * Like [complete], but hands each text fragment to [onTextDelta] as it arrives (chat, voice
     * training). The result still carries the full answer, tool calls and token usage.
     */
    fun stream(
        request: LlmRequest,
        onTextDelta: (String) -> Unit,
    ): AiResult<LlmResponse>
}
