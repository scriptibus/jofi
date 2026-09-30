// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.application.port.AiProviderPort
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.shared.application.port.LlmPort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmResponse

/**
 * Known-bad: a second `LlmPort` in the AI adapter's own package that calls the provider directly,
 * skipping the gateway's filter and meter. Test fixture only, never production.
 */
class ShortcutLlmFixture(
    private val provider: AiProviderPort,
    private val target: ResolvedModel,
) : LlmPort {
    override fun complete(request: LlmRequest): AiResult<LlmResponse> = provider.complete(target, request)

    override fun stream(
        request: LlmRequest,
        isCancelled: () -> Boolean,
        onTextDelta: (String) -> Unit,
    ): AiResult<LlmResponse> = provider.stream(target, request, isCancelled, {}, onTextDelta)
}
