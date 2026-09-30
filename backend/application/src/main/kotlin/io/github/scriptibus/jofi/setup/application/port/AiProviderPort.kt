// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port

import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingResponse
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse

/**
 * Provider-facing AI calls (ADR-0032), implemented with Spring AI in `setup.adapter.ai` (#19). It
 * sends a request to exactly the given [ResolvedModel]: no routing, no capability check, no filter,
 * no metering; the AI gateway has done all of that before and resolved the target once per call.
 * The implementation reads the API key through `SecretStorePort`, and it never throws.
 *
 * Only `setup.adapter.ai` may depend on this port (architecture test): everything else calls the
 * task-based `LlmPort` / `EmbeddingPort`, so no call can bypass the privacy filter or the meter.
 */
interface AiProviderPort {
    fun complete(
        target: ResolvedModel,
        request: LlmRequest,
    ): AiResult<LlmResponse>

    /** Streaming variant; cancellation and consumer failures behave as in `LlmPort.stream`. */
    fun stream(
        target: ResolvedModel,
        request: LlmRequest,
        isCancelled: () -> Boolean,
        onTextDelta: (String) -> Unit,
    ): AiResult<LlmResponse>

    fun embed(
        target: ResolvedModel,
        request: EmbeddingRequest,
    ): AiResult<EmbeddingResponse>
}
