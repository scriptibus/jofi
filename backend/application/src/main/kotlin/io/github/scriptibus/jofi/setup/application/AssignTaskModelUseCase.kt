// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.AssignTaskModelPort
import io.github.scriptibus.jofi.setup.domain.CapabilityInput
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.setup.domain.TaskAssignmentView
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import java.time.Clock

/**
 * Assigns a provider's model to a task (spec §3.2 per-task model selection). A model lacking what the
 * task needs is still assigned, since the user may choose a weaker model; the answer carries the
 * warnings. Unchanged assignments write nothing.
 */
class AssignTaskModelUseCase(
    private val providers: ProviderConfigPort,
    private val assignments: ModelAssignmentPort,
    private val profiles: ModelCapabilityPort,
    private val catalog: ModelCatalogPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : AssignTaskModelPort {
    override fun execute(
        task: AiTask,
        provider: ProviderId,
        model: String,
        actor: Actor,
    ): SetupResult<TaskAssignmentView> =
        asUser(actor) {
            CapabilityInput.modelName(model).toSetupResult().then { name ->
                providers.findById(provider).toSetupResult().then { config ->
                    assignments.findByTask(task).orNull().then { before ->
                        assign(ModelAssignment(task, provider, name), config, before, actor)
                    }
                }
            }
        }

    private fun assign(
        assignment: ModelAssignment,
        config: ProviderConfig,
        before: ModelAssignment?,
        actor: Actor,
    ): SetupResult<TaskAssignmentView> {
        val view = assignmentView(assignment.task, assignment, config, profiles, catalog)
        return when {
            view == null -> SetupResult.StorageFailure("models")
            assignment == before -> SetupResult.Success(view)
            else -> transactions.whenSuccessful { store(before, assignment, actor, view) }
        }
    }

    private fun store(
        before: ModelAssignment?,
        assignment: ModelAssignment,
        actor: Actor,
        view: TaskAssignmentView,
    ): SetupResult<TaskAssignmentView> {
        val changes =
            listOfNotNull(
                changeOf("provider", before?.provider?.value, assignment.provider.value),
                changeOf("model", before?.model?.value, assignment.model.value),
            )
        val entity = ModelAssignment.entityRef(assignment.task)
        return when {
            assignments.save(assignment) !is SetupStoreResult.Success -> {
                SetupResult.StorageFailure("save assignment")
            }

            !changelog.record(entity, actor, clock.storedNow(), "Assigned a model to the task", changes) -> {
                SetupResult.StorageFailure("changelog")
            }

            else -> {
                SetupResult.Success(view)
            }
        }
    }
}
