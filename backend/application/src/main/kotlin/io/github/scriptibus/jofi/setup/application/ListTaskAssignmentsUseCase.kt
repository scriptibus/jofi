// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.ListTaskAssignmentsPort
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.TaskAssignmentView
import io.github.scriptibus.jofi.shared.domain.ai.AiTask

/** Every AI task, its assigned model (if any) and the capability warnings for that model. */
class ListTaskAssignmentsUseCase(
    private val assignments: ModelAssignmentPort,
    private val providers: ProviderConfigPort,
    private val profiles: ModelCapabilityPort,
    private val catalog: ModelCatalogPort,
) : ListTaskAssignmentsPort {
    override fun execute(): SetupResult<List<TaskAssignmentView>> =
        assignments.findAll().toSetupResult().then { assigned ->
            providers.findAll().toSetupResult().then { configured -> views(assigned, configured) }
        }

    private fun views(
        assigned: List<ModelAssignment>,
        configured: List<ProviderConfig>,
    ): SetupResult<List<TaskAssignmentView>> {
        val byTask = assigned.associateBy { it.task }
        val byId = configured.associateBy { it.id }
        val views =
            AiTask.entries.map { task ->
                val assignment = byTask[task]
                assignmentView(task, assignment, assignment?.let { byId[it.provider] }, profiles, catalog)
            }
        val complete = views.filterNotNull()
        return if (complete.size == views.size) SetupResult.Success(complete) else SetupResult.StorageFailure("models")
    }
}
