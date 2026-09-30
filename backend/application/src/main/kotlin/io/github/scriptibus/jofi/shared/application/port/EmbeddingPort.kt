// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingResponse

/**
 * Text embeddings for semantic search, addressed by task (ADR-0011, ADR-0032). Implemented by the
 * same AI gateway as [LlmPort], with the same routing, filter and metering. Implementations never
 * throw and return one embedding per text, in request order.
 */
interface EmbeddingPort {
    fun embed(request: EmbeddingRequest): AiResult<EmbeddingResponse>
}
