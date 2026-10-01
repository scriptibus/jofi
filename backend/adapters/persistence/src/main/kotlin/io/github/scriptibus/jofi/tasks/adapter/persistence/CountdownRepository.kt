// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COUNTDOWN
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.CountdownRecord
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.tasks.application.port.CountdownRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownDetails
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneOffset

/**
 * Custom countdowns (`countdown`), in the caller's transaction. Changes store only on top of the version they were
 * based on; the delete needs the confirmation proof. Titles are the user's words, so only operations and exception
 * types are logged.
 */
@Component
class CountdownRepository(
    private val dsl: DSLContext,
) : CountdownRepositoryPort {
    override fun add(countdown: Countdown): TaskStoreResult<Unit> =
        storeCall("add") {
            dsl.insertInto(COUNTDOWN).set(toRecord(countdown)).execute()
            TaskStoreResult.Success(Unit)
        }

    override fun update(countdown: Countdown): TaskStoreResult<Unit> =
        storeCall("update") {
            val updated =
                dsl
                    .update(COUNTDOWN)
                    .set(toRecord(countdown))
                    .where(COUNTDOWN.ID.eq(countdown.id.value))
                    .and(COUNTDOWN.VERSION.eq(countdown.version - 1))
                    .execute()
            when {
                updated == 1 -> TaskStoreResult.Success(Unit)
                dsl.fetchExists(COUNTDOWN, COUNTDOWN.ID.eq(countdown.id.value)) -> TaskStoreResult.VersionConflict
                else -> TaskStoreResult.NotFound
            }
        }

    override fun findById(id: CountdownId): TaskStoreResult<Countdown> =
        storeCall("findById") {
            dsl
                .fetchOne(COUNTDOWN, COUNTDOWN.ID.eq(id.value))
                ?.let { TaskStoreResult.Success(toDomain(it)) }
                ?: TaskStoreResult.NotFound
        }

    override fun list(): TaskStoreResult<List<Countdown>> =
        storeCall("list") {
            val all =
                dsl
                    .selectFrom(COUNTDOWN)
                    .orderBy(COUNTDOWN.TARGET_DATE, COUNTDOWN.ID)
                    .fetch()
                    .map(::toDomain)
            TaskStoreResult.Success(all)
        }

    override fun delete(
        id: CountdownId,
        proof: ConfirmationResult.Confirmed,
    ): TaskStoreResult<Unit> {
        if (!proof.covers(Countdown.DELETE_OPERATION, id.value.toString())) return TaskStoreResult.NotConfirmed
        return storeCall("delete") {
            val deleted = dsl.deleteFrom(COUNTDOWN).where(COUNTDOWN.ID.eq(id.value)).execute()
            if (deleted == 0) TaskStoreResult.NotFound else TaskStoreResult.Success(Unit)
        }
    }

    private fun toRecord(countdown: Countdown): CountdownRecord =
        CountdownRecord().apply {
            id = countdown.id.value
            title = countdown.details.title
            targetDate = countdown.details.targetDate
            version = countdown.version
            createdAt = countdown.createdAt.atOffset(ZoneOffset.UTC)
            updatedAt = countdown.updatedAt.atOffset(ZoneOffset.UTC)
        }

    private fun toDomain(record: CountdownRecord): Countdown =
        Countdown(
            CountdownId(record.id),
            CountdownDetails(record.title, record.targetDate),
            record.version,
            record.createdAt.toInstant(),
            record.updatedAt.toInstant(),
        )

    /** No exception crosses the port; exception messages can carry row values, so only the type is logged. */
    private fun <T> storeCall(
        operation: String,
        block: () -> TaskStoreResult<T>,
    ): TaskStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            log.error("Countdown store {} failed: {}", operation, exception.javaClass.name)
            TaskStoreResult.StorageFailure(operation)
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(CountdownRepository::class.java)
    }
}
