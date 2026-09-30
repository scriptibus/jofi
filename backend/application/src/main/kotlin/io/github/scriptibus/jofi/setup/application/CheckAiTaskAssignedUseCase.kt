// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort.Assignment
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask

/** Whether an [AiTask] has a model assigned, for other contexts (#96). Reads only. */
class CheckAiTaskAssignedUseCase(
    private val assignments: ModelAssignmentPort,
) : CheckAiTaskAssignedPort {
    override fun execute(task: AiTask): Assignment =
        when (assignments.findByTask(task)) {
            is SetupStoreResult.Success -> Assignment.Assigned
            SetupStoreResult.NotFound -> Assignment.NotAssigned
            else -> Assignment.Unavailable
        }
}
