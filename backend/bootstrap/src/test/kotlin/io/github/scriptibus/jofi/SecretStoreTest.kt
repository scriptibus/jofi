// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SECRET
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.github.scriptibus.jofi.system.application.port.UserAccountPort
import io.github.scriptibus.jofi.system.domain.AccountId
import io.github.scriptibus.jofi.system.domain.PasswordHash
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * The encrypted secret store as wired in the app: Tink AES-GCM (master keyset in the data volume)
 * in front of the `secret` table, against a real PostgreSQL. Also the transaction port that keeps a
 * mutation and its changelog entry together.
 */
@SpringBootTest
@AutoConfigureMockMvc // Same context as the other app tests, so it is started once.
@Import(PostgresTestConfiguration::class)
class SecretStoreTest(
    @param:Autowired private val secrets: SecretStorePort,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val transactions: TransactionPort,
    @param:Autowired private val users: UserAccountPort,
    @param:Autowired private val changelog: ChangelogPort,
) {
    private val id = SecretId(UUID.randomUUID())
    private val apiKey = SecretValue("sk-ant-test-0123456789abcdef")

    @BeforeEach
    fun store() {
        secrets.put(id, apiKey) shouldBe SecretResult.Success(Unit)
    }

    private fun ciphertext(of: SecretId = id): ByteArray = dsl.fetchValue(SECRET.CIPHERTEXT, SECRET.ID.eq(of.value))

    @Test
    fun `a secret round-trips and the database holds only ciphertext`() {
        secrets.get(id) shouldBe SecretResult.Success(apiKey)

        String(ciphertext(), Charsets.ISO_8859_1).contains(apiKey.reveal()) shouldBe false
    }

    @Test
    fun `a tampered ciphertext is detected`() {
        val tampered = ciphertext().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        dsl
            .update(SECRET)
            .set(SECRET.CIPHERTEXT, tampered)
            .where(SECRET.ID.eq(id.value))
            .execute()

        secrets.get(id) shouldBe SecretResult.Undecryptable
    }

    @Test
    fun `a ciphertext copied to another secret id does not decrypt (associated data)`() {
        val other = SecretId(UUID.randomUUID())
        secrets.put(other, SecretValue("another key"))
        dsl
            .update(SECRET)
            .set(SECRET.CIPHERTEXT, ciphertext())
            .where(SECRET.ID.eq(other.value))
            .execute()

        secrets.get(other) shouldBe SecretResult.Undecryptable
        secrets.get(id) shouldBe SecretResult.Success(apiKey)
    }

    @Test
    fun `replacing and deleting a secret`() {
        secrets.put(id, SecretValue("rotated key")) shouldBe SecretResult.Success(Unit)
        secrets.get(id) shouldBe SecretResult.Success(SecretValue("rotated key"))

        secrets.delete(id) shouldBe SecretResult.Success(Unit)
        secrets.get(id) shouldBe SecretResult.NotFound
    }

    @Test
    fun `a transaction that is not committed leaves neither the mutation nor its changelog entry`() {
        dsl.deleteFrom(USER_ACCOUNT).execute()
        val now = Instant.parse("2026-09-30T12:00:00Z")
        val entity = EntityRef("transaction-test", UUID.randomUUID().toString())

        val result =
            transactions.inTransaction({ false }) {
                users.create(UserAccount(AccountId(UUID.randomUUID()), PasswordHash("\$argon2id\$rollback"), now, now))
                changelog.append(ChangelogEntry(entity, Actor.User, now, ChangeSummary("Rolled back")))
            }

        result shouldBe ChangelogResult.Success(Unit)
        users.find() shouldBe UserAccountStoreResult.Success(null)
        (changelog.listByEntity(entity, ChangelogLimit(1)) as ChangelogResult.Success).value.shouldBeEmpty()
    }
}
