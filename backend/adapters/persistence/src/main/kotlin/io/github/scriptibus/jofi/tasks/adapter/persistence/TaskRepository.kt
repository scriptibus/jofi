// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.TASK
import io.github.scriptibus.jofi.shared.adapter.persistence.violatedConstraint
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.paging.PageInfo
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.paging.Paged
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Tasks (`task`), in the caller's transaction. Changes store only on top of the version they were based on; the
 * delete needs the confirmation proof. A link to something that does not exist and a repeated suggestion are
 * recognised by the name of the violated constraint (ADR-0041). Titles and notes may describe other people, so only
 * operations and exception types are logged.
 */
@Component
class TaskRepository(
    private val dsl: DSLContext,
) : TaskRepositoryPort {
    override fun add(task: Task): TaskStoreResult<Unit> =
        storeCall("add") {
            dsl.insertInto(TASK).set(TaskRecords.toRecord(task)).execute()
            TaskStoreResult.Success(Unit)
        }

    override fun update(task: Task): TaskStoreResult<Unit> =
        storeCall("update") {
            val updated =
                dsl
                    .update(TASK)
                    .set(TaskRecords.toRecord(task))
                    .where(TASK.ID.eq(task.id.value))
                    .and(TASK.VERSION.eq(task.version - 1))
                    .execute()
            when {
                updated == 1 -> TaskStoreResult.Success(Unit)
                dsl.fetchExists(TASK, TASK.ID.eq(task.id.value)) -> TaskStoreResult.VersionConflict
                else -> TaskStoreResult.NotFound
            }
        }

    override fun findById(id: TaskId): TaskStoreResult<Task> =
        storeCall("findById") {
            dsl
                .fetchOne(TASK, TASK.ID.eq(id.value))
                ?.let { TaskStoreResult.Success(TaskRecords.toDomain(it)) }
                ?: TaskStoreResult.NotFound
        }

    override fun listByState(state: TaskState): TaskStoreResult<List<Task>> =
        storeCall("listByState") { TaskStoreResult.Success(list(TASK.STATE.eq(state.name))) }

    override fun pageByStateNewestFirst(
        state: TaskState,
        request: PageRequest,
    ): TaskStoreResult<Paged<Task>> =
        storeCall("pageByStateNewestFirst") {
            val inState = TASK.STATE.eq(state.name)
            val total = dsl.fetchCount(TASK, inState)
            val items =
                dsl
                    .selectFrom(TASK)
                    .where(inState)
                    .orderBy(TASK.CREATED_AT.desc(), TASK.ID.desc())
                    .limit(request.size)
                    .offset(request.offset)
                    .fetch()
                    .map(TaskRecords::toDomain)
            TaskStoreResult.Success(Paged(items, PageInfo.of(request, total)))
        }

    /**
     * One page of the done tasks, the newest completion first (then by id). The page and the total come from one
     * statement (`count(*) OVER ()`), so a task completed or reopened by someone else meanwhile cannot make the total
     * smaller than the page. Only a page past the end has no row to carry the total; it is empty, so a count of its
     * own cannot contradict it.
     */
    override fun listDone(request: PageRequest): TaskStoreResult<Paged<Task>> =
        storeCall("listDone") {
            val done = TASK.STATE.eq(TaskState.DONE.name)
            val total = DSL.count().over().`as`("done_total")
            val rows =
                dsl
                    .select(TASK.asterisk(), total)
                    .from(TASK)
                    .where(done)
                    .orderBy(TASK.COMPLETED_AT.desc(), TASK.ID.desc())
                    .limit(request.size)
                    .offset(request.offset)
                    .fetch()
            val count = rows.firstOrNull()?.get(total) ?: dsl.fetchCount(TASK, done)
            TaskStoreResult.Success(
                Paged(rows.map { TaskRecords.toDomain(it.into(TASK)) }, PageInfo.of(request, count)),
            )
        }

    override fun listByLink(link: TaskLink): TaskStoreResult<List<Task>> =
        storeCall("listByLink") {
            val column =
                when (link) {
                    is ApplicationRef -> TASK.APPLICATION_ID
                    is CompanyRef -> TASK.COMPANY_ID
                    is ContactRef -> TASK.CONTACT_ID
                }
            TaskStoreResult.Success(list(column.eq(link.value)))
        }

    override fun delete(
        id: TaskId,
        proof: ConfirmationResult.Confirmed,
    ): TaskStoreResult<Unit> {
        if (!proof.covers(Task.DELETE_OPERATION, id.value.toString())) return TaskStoreResult.NotConfirmed
        return storeCall("delete") {
            val deleted = dsl.deleteFrom(TASK).where(TASK.ID.eq(id.value)).execute()
            if (deleted == 0) TaskStoreResult.NotFound else TaskStoreResult.Success(Unit)
        }
    }

    /** Oldest first, then by id, so the order is stable. */
    private fun list(condition: Condition): List<Task> =
        dsl
            .selectFrom(TASK)
            .where(condition)
            .orderBy(TASK.CREATED_AT, TASK.ID)
            .fetch()
            .map(TaskRecords::toDomain)

    /** No exception crosses the port; exception messages can carry row values, so only the type is logged. */
    private fun <T> storeCall(
        operation: String,
        block: () -> TaskStoreResult<T>,
    ): TaskStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            when (exception.violatedConstraint()) {
                in LINK_FKS -> {
                    TaskStoreResult.LinkNotFound
                }

                SUGGESTION_UNIQUE -> {
                    TaskStoreResult.SuggestionExists
                }

                else -> {
                    log.error("Task store {} failed: {}", operation, exception.javaClass.name)
                    TaskStoreResult.StorageFailure(operation)
                }
            }
        }

    private companion object {
        /** `task.application_id`, `task.company_id`, `task.contact_id`: the link names something that is not there. */
        val LINK_FKS = setOf("task_application_fk", "task_company_fk", "task_contact_fk")

        /** `(suggestion_rule, suggestion_key)`: the rule suggested this already (#95). */
        const val SUGGESTION_UNIQUE = "task_suggestion_unique"

        val log: Logger = LoggerFactory.getLogger(TaskRepository::class.java)
    }
}
