// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_ASSIGNMENT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.AiModelAssignmentRecord
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** The per-task model assignments (`ai_model_assignment`, one row per task). The AI gateway reads it per call. */
@Component
class ModelAssignmentRepository(
    private val dsl: DSLContext,
) : ModelAssignmentPort {
    override fun findAll(): SetupStoreResult<List<ModelAssignment>> =
        storeCall(log, "findAll") {
            SetupStoreResult.Success(
                dsl
                    .selectFrom(AI_MODEL_ASSIGNMENT)
                    .orderBy(AI_MODEL_ASSIGNMENT.TASK)
                    .fetch()
                    .map(::toDomain),
            )
        }

    override fun findByTask(task: AiTask): SetupStoreResult<ModelAssignment> =
        storeCall(log, "findByTask") {
            dsl.fetchOne(AI_MODEL_ASSIGNMENT, AI_MODEL_ASSIGNMENT.TASK.eq(task.name))?.let(::toDomain).foundOrNotFound()
        }

    override fun save(assignment: ModelAssignment): SetupStoreResult<Unit> =
        storeCall(log, "save") {
            dsl
                .insertInto(AI_MODEL_ASSIGNMENT)
                .set(AI_MODEL_ASSIGNMENT.TASK, assignment.task.name)
                .set(AI_MODEL_ASSIGNMENT.PROVIDER_ID, assignment.provider.value)
                .set(AI_MODEL_ASSIGNMENT.MODEL, assignment.model.value)
                .onConflict(AI_MODEL_ASSIGNMENT.TASK)
                .doUpdate()
                .set(AI_MODEL_ASSIGNMENT.PROVIDER_ID, assignment.provider.value)
                .set(AI_MODEL_ASSIGNMENT.MODEL, assignment.model.value)
                .execute()
            SetupStoreResult.Success(Unit)
        }

    override fun delete(task: AiTask): SetupStoreResult<Unit> =
        storeCall(log, "delete") {
            val deleted = dsl.deleteFrom(AI_MODEL_ASSIGNMENT).where(AI_MODEL_ASSIGNMENT.TASK.eq(task.name)).execute()
            if (deleted == 0) SetupStoreResult.NotFound else SetupStoreResult.Success(Unit)
        }

    private fun toDomain(record: AiModelAssignmentRecord): ModelAssignment =
        ModelAssignment(AiTask.valueOf(record.task), ProviderId(record.providerId), ModelName(record.model))

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ModelAssignmentRepository::class.java)
    }
}
