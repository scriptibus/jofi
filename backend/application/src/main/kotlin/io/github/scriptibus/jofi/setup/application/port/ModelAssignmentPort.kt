// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application.port

import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask

/**
 * Stores the per-task model assignments (table `ai_model_assignment`, one row per task;
 * implemented in #23). Callers of [save] and [delete] append a changelog entry. Never throws.
 */
interface ModelAssignmentPort {
    fun findAll(): SetupStoreResult<List<ModelAssignment>>

    fun findByTask(task: AiTask): SetupStoreResult<ModelAssignment>

    /** Inserts or replaces the assignment of the same task. */
    fun save(assignment: ModelAssignment): SetupStoreResult<Unit>

    fun delete(task: AiTask): SetupStoreResult<Unit>
}
