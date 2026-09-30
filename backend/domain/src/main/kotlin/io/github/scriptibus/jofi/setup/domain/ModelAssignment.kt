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
 * Which [model] of which [provider] runs [task] (spec §3.2 per-task model selection), together
 * with what that model can do. At most one assignment exists per task.
 */
data class ModelAssignment(
    val task: AiTask,
    val provider: ProviderId,
    val model: ModelName,
    val capabilities: ModelCapabilities,
)
