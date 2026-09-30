// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SECRET
import io.github.scriptibus.jofi.shared.application.port.SecretCipherPort
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset

/**
 * The encrypted secret store on the `secret` table (ADR-0017). Values are encrypted by
 * [SecretCipherPort] (Tink AES-GCM, bound to the secret id) before they reach the database and are
 * decrypted only on [get]; clear text never touches SQL, logs or error results.
 */
@Component
class SecretRepository(
    private val dsl: DSLContext,
    private val cipher: SecretCipherPort,
    private val clock: Clock,
) : SecretStorePort {
    override fun put(
        id: SecretId,
        value: SecretValue,
    ): SecretResult<Unit> =
        when (val encrypted = cipher.encrypt(id, value)) {
            is SecretResult.Success -> store(id, encrypted.value)
            is SecretResult.StorageFailure -> encrypted
            SecretResult.NotFound, SecretResult.Undecryptable -> SecretResult.StorageFailure(PUT)
        }

    private fun store(
        id: SecretId,
        ciphertext: ByteArray,
    ): SecretResult<Unit> =
        guarded(PUT) {
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            dsl
                .insertInto(SECRET)
                .set(SECRET.ID, id.value)
                .set(SECRET.CIPHERTEXT, ciphertext)
                .set(SECRET.CREATED_AT, now)
                .set(SECRET.UPDATED_AT, now)
                .onConflict(SECRET.ID)
                .doUpdate()
                .set(SECRET.CIPHERTEXT, ciphertext)
                .set(SECRET.UPDATED_AT, now)
                .execute()
            SecretResult.Success(Unit)
        }

    override fun get(id: SecretId): SecretResult<SecretValue> =
        guarded("get") {
            val ciphertext =
                dsl
                    .select(
                        SECRET.CIPHERTEXT,
                    ).from(SECRET)
                    .where(SECRET.ID.eq(id.value))
                    .fetchOne(SECRET.CIPHERTEXT)
            if (ciphertext == null) SecretResult.NotFound else cipher.decrypt(id, ciphertext)
        }

    override fun delete(id: SecretId): SecretResult<Unit> =
        guarded("delete") {
            val deleted = dsl.deleteFrom(SECRET).where(SECRET.ID.eq(id.value)).execute()
            if (deleted == 0) SecretResult.NotFound else SecretResult.Success(Unit)
        }

    // No exception crosses the port; only the operation and the exception type are logged.
    private fun <T> guarded(
        operation: String,
        block: () -> SecretResult<T>,
    ): SecretResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            logger.error("Secret store {} failed: {}", operation, exception.javaClass.name)
            SecretResult.StorageFailure(operation)
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SecretRepository::class.java)
        const val PUT = "put"
    }
}
