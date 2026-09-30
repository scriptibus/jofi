// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse

/**
 * Text generation with a language model, addressed by task (ADR-0011, ADR-0032). Callers in every
 * context depend on this port only. It is implemented once, by the AI gateway in
 * `setup.adapter.ai` (#20): routing by the task's model assignment, capability check, the "never
 * send to AI" filter and cost metering, then a call through `AiProviderPort`. Implementations never
 * throw: failures come back as [AiResult] variants. Calls block; they run on virtual threads.
 */
interface LlmPort {
    /** Sends [request] and returns the complete answer with its token usage. */
    fun complete(request: LlmRequest): AiResult<LlmResponse>

    /**
     * Like [complete], but hands each text fragment to [onTextDelta] as it arrives (chat, voice
     * training). The result still carries the full answer, tool calls and token usage.
     *
     * Cancellation: the implementation polls [isCancelled] between fragments; once it returns true
     * it stops the provider stream and returns [AiResult.Cancelled]. If [onTextDelta] throws, the
     * implementation catches it, stops the stream and returns [AiResult.Cancelled] as well: no
     * exception crosses the port in either direction.
     */
    fun stream(
        request: LlmRequest,
        isCancelled: () -> Boolean = { false },
        onTextDelta: (String) -> Unit,
    ): AiResult<LlmResponse>
}
