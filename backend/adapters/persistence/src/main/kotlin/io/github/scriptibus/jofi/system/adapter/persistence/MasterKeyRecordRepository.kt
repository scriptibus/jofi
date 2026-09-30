// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.MASTER_KEY_CHECK
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SECRET
import io.github.scriptibus.jofi.system.application.port.MasterKeyRecordPort
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset

/** The check value of the master keyset (`master_key_check`) and whether `secret` holds rows. */
@Component
class MasterKeyRecordRepository(
    private val dsl: DSLContext,
) : MasterKeyRecordPort {
    override fun findCheckValue(): SystemStoreResult<ByteArray?> =
        guarded("findCheckValue") {
            dsl.select(MASTER_KEY_CHECK.CHECK_VALUE).from(MASTER_KEY_CHECK).fetchOne(MASTER_KEY_CHECK.CHECK_VALUE)
        }

    override fun saveCheckValue(
        checkValue: ByteArray,
        recordedAt: Instant,
    ): SystemStoreResult<Unit> =
        guarded("saveCheckValue") {
            val at = recordedAt.atOffset(ZoneOffset.UTC)
            dsl
                .insertInto(MASTER_KEY_CHECK)
                .set(MASTER_KEY_CHECK.CHECK_VALUE, checkValue)
                .set(MASTER_KEY_CHECK.RECORDED_AT, at)
                .onConflict(MASTER_KEY_CHECK.SINGLETON)
                .doUpdate()
                .set(MASTER_KEY_CHECK.CHECK_VALUE, checkValue)
                .set(MASTER_KEY_CHECK.RECORDED_AT, at)
                .execute()
                .let { }
        }

    override fun hasSecrets(): SystemStoreResult<Boolean> = guarded("hasSecrets") { dsl.fetchExists(SECRET) }

    // No exception crosses the port; only the operation and the exception type are logged.
    private fun <T> guarded(
        operation: String,
        block: () -> T,
    ): SystemStoreResult<T> =
        try {
            SystemStoreResult.Success(block())
        } catch (exception: RuntimeException) {
            logger.error("Master key record {} failed: {}", operation, exception.javaClass.name)
            SystemStoreResult.StorageFailure(operation)
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(MasterKeyRecordRepository::class.java)
    }
}
