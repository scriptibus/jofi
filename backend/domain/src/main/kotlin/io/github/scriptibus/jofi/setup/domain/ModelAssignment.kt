// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.AiTask

/** A provider's model identifier, e.g. `claude-sonnet-4-5` or `llama3.1:8b`. */
@JvmInline
value class ModelName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A model name must not be blank" }
    }
}

/**
 * Which [model] of which [provider] runs [task] (spec §3.2 per-task model selection). At most one
 * assignment exists per task. What the model can do is a [ModelCapabilityProfile] of the provider
 * and model, not part of the assignment.
 */
data class ModelAssignment(
    val task: AiTask,
    val provider: ProviderId,
    val model: ModelName,
)

/**
 * The provider and model an AI call goes to, resolved once per call from the task's assignment by
 * the AI gateway and handed to `AiProviderPort` (ADR-0032). The provider config carries a secret
 * id; the provider adapter reads the key itself through `SecretStorePort`.
 */
data class ResolvedModel(
    val provider: ProviderConfig,
    val model: ModelName,
)
