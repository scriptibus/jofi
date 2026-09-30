// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SECRET
import io.github.scriptibus.jofi.shared.application.port.SecretCipherPort
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The SQL side of the secret store against a real PostgreSQL. Encryption is a reversible stand-in
 * here; the real Tink cipher with tamper and associated-data checks is tested in adapters/crypto and
 * end to end in bootstrap (`SecretStoreTest`).
 */
class SecretRepositoryTest {
    private lateinit var dsl: DSLContext
    private val id = SecretId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val first = Instant.parse("2026-09-30T10:00:00Z")
    private var now = first
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant() = now
        }

    /** Reverses the bytes and prefixes the id, so a ciphertext only "decrypts" for its own id. */
    private val cipher =
        object : SecretCipherPort {
            override fun encrypt(
                id: SecretId,
                value: SecretValue,
            ) = SecretResult.Success("${id.value}|${value.reveal().reversed()}".toByteArray())

            override fun decrypt(
                id: SecretId,
                ciphertext: ByteArray,
            ): SecretResult<SecretValue> {
                val (owner, body) = String(ciphertext).split("|", limit = 2)
                return if (owner ==
                    id.value.toString()
                ) {
                    SecretResult.Success(SecretValue(body.reversed()))
                } else {
                    SecretResult.Undecryptable
                }
            }
        }

    private lateinit var repository: SecretRepository

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = SecretRepository(dsl, cipher, clock)
    }

    @Test
    fun `a secret is stored as ciphertext and read back in clear`() {
        repository.put(id, SecretValue("sk-live-123")) shouldBe SecretResult.Success(Unit)

        repository.get(id) shouldBe SecretResult.Success(SecretValue("sk-live-123"))
        String(dsl.fetchValue(SECRET.CIPHERTEXT, SECRET.ID.eq(id.value))).contains("sk-live-123") shouldBe false
    }

    @Test
    fun `putting again replaces the value and keeps the creation time`() {
        repository.put(id, SecretValue("old key"))
        now = first.plusSeconds(60)

        repository.put(id, SecretValue("new key")) shouldBe SecretResult.Success(Unit)

        repository.get(id) shouldBe SecretResult.Success(SecretValue("new key"))
        val row = dsl.fetchOne(SECRET, SECRET.ID.eq(id.value))
        row?.createdAt?.toInstant() shouldBe first
        row?.updatedAt?.toInstant() shouldBe first.plusSeconds(60)
    }

    @Test
    fun `missing secrets are reported and deletes remove the row`() {
        repository.get(id) shouldBe SecretResult.NotFound
        repository.delete(id) shouldBe SecretResult.NotFound

        repository.put(id, SecretValue("key"))
        repository.delete(id) shouldBe SecretResult.Success(Unit)
        repository.get(id) shouldBe SecretResult.NotFound
    }

    @Test
    fun `a failing cipher or database is a value, not an exception`() {
        val refusing =
            object : SecretCipherPort by cipher {
                override fun encrypt(
                    id: SecretId,
                    value: SecretValue,
                ) = SecretResult.StorageFailure("encrypt")
            }
        SecretRepository(dsl, refusing, clock).put(id, SecretValue("key")) shouldBe
            SecretResult.StorageFailure("encrypt")

        val broken = SecretRepository(DSL.using(SQLDialect.POSTGRES), cipher, clock)
        broken.put(id, SecretValue("key")) shouldBe SecretResult.StorageFailure("put")
        broken.get(id) shouldBe SecretResult.StorageFailure("get")
        broken.delete(id) shouldBe SecretResult.StorageFailure("delete")
    }
}
