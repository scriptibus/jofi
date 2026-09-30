// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask

/** Where a call goes and what that model can do, or why it cannot go anywhere. */
sealed interface Route {
    data class Resolved(
        val target: ResolvedModel,
        val capabilities: ModelCapabilities,
    ) : Route

    data class Unroutable(
        val result: AiResult<Nothing>,
    ) : Route
}

/**
 * Resolves a task to its provider and model, once per call (ADR-0032): the assignment, the provider
 * config and the model's capabilities. A stored capability profile (detected or corrected by the
 * user) wins; without one, the adapter's table of known models answers. A missing assignment or
 * provider is [AiResult.NotConfigured]; a store failure is [AiResult.Unavailable].
 */
class AiRouter(
    private val assignments: ModelAssignmentPort,
    private val providers: ProviderConfigPort,
    private val capabilities: ModelCapabilityPort,
    private val catalog: ModelCatalogPort,
) {
    fun route(task: AiTask): Route =
        when (val assignment = assignments.findByTask(task)) {
            is SetupStoreResult.Success -> routeTo(task, assignment.value)
            else -> unroutable(task, assignment)
        }

    private fun routeTo(
        task: AiTask,
        assignment: ModelAssignment,
    ): Route =
        when (val provider = providers.findById(assignment.provider)) {
            is SetupStoreResult.Success -> withCapabilities(task, provider.value, assignment.model)
            else -> unroutable(task, provider)
        }

    private fun withCapabilities(
        task: AiTask,
        provider: ProviderConfig,
        model: ModelName,
    ): Route =
        when (val found = capabilities.find(provider.id, model)) {
            is SetupStoreResult.Success -> {
                Route.Resolved(ResolvedModel(provider, model), found.value.capabilities)
            }

            SetupStoreResult.NotFound -> {
                Route.Resolved(
                    ResolvedModel(provider, model),
                    catalog.knownCapabilities(provider.kind, model),
                )
            }

            else -> {
                unroutable(task, found)
            }
        }

    private fun unroutable(
        task: AiTask,
        failure: SetupStoreResult<*>,
    ): Route.Unroutable =
        Route.Unroutable(
            if (failure == SetupStoreResult.NotFound) AiResult.NotConfigured(task) else AiResult.Unavailable,
        )
}
