// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import java.time.Instant

/**
 * What one AI call cost (spec §3.2 cost tracking): which [task] ran on which [provider] and
 * [model], the tokens it used and the cost estimated from the price table. Cost entries are an
 * append-only meter; summaries per task, provider and month are computed from them.
 */
data class CostEntry(
    val task: AiTask,
    val provider: ProviderId,
    val model: ModelName,
    val usage: TokenUsage,
    val estimatedCost: Money,
    val occurredAt: Instant,
)
