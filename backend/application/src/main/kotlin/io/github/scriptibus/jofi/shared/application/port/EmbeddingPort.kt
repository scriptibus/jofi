// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingResponse

/**
 * Text embeddings for semantic search (ADR-0011). Same routing, filter and metering rules as
 * [LlmPort]. Implementations never throw and return one embedding per text, in request order.
 */
interface EmbeddingPort {
    fun embed(request: EmbeddingRequest): AiResult<EmbeddingResponse>
}
