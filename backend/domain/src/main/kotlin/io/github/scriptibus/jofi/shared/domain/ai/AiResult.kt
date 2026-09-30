// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.ai

import java.time.Duration

/**
 * Outcome of an AI port call. Every expected failure is a variant, never an exception, so a failed
 * AI call cannot lose user data (spec §13 Reliability). No variant carries prompt or response
 * content, so results are safe to log (threat model T4).
 */
sealed interface AiResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : AiResult<T>

    /** No provider/model is assigned to [task] yet. */
    data class NotConfigured(
        val task: AiTask,
    ) : AiResult<Nothing>

    /** The model assigned to [task] lacks a capability the request needs; the provider was not called. */
    data class CapabilityMissing(
        val task: AiTask,
    ) : AiResult<Nothing>

    /** The monthly budget cap is reached and [task] is non-essential; the provider was not called. */
    data class BudgetExceeded(
        val task: AiTask,
    ) : AiResult<Nothing>

    /** The provider rejected the configured key. */
    data object AuthenticationFailed : AiResult<Nothing>

    /** The provider throttled the call; retry after [retryAfter] when it said so. */
    data class RateLimited(
        val retryAfter: Duration?,
    ) : AiResult<Nothing>

    /** The request does not fit the model's context window. */
    data object ContextTooLong : AiResult<Nothing>

    /** The provider could not be reached, timed out or failed on its side. Retrying may help. */
    data object Unavailable : AiResult<Nothing>

    /** The provider refused the request for another reason; [statusCode] when it answered over HTTP. */
    data class Rejected(
        val statusCode: Int?,
    ) : AiResult<Nothing>
}
