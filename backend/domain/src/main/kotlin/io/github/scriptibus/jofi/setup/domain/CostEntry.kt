// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import java.time.Instant

/**
 * What one AI call cost (spec §3.2 cost tracking): which [task] ran on which [provider] and
 * [model], the tokens it used and the cost estimated from the price table, in the accounting
 * currency (USD, ADR-0032). [providerKind] is a snapshot, so the history stays readable after the
 * provider config is deleted. Cost entries are an append-only meter; summaries per task, provider
 * and month are computed from them.
 *
 * [estimatedCost] is null when it is unknown: the model has no price in the table (every
 * OpenAI-compatible endpoint, until the user sets one), or the provider reported no usage (a stream
 * cancelled before its usage arrived). Unknown costs are never guessed (ADR-0043).
 */
data class CostEntry(
    val task: AiTask,
    val provider: ProviderId,
    val providerKind: ProviderKind,
    val model: ModelName,
    val usage: TokenUsage,
    val estimatedCost: Money?,
    val occurredAt: Instant,
) {
    init {
        require(estimatedCost?.isAccountingCurrency ?: true) { "Costs are recorded in ${Money.ACCOUNTING_CURRENCY}" }
    }
}
