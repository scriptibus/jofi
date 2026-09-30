// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.system.domain.AccountId
import io.github.scriptibus.jofi.system.domain.PasswordHash
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.github.scriptibus.jofi.system.domain.UserAccountStoreResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** jOOQ round-trips of the single-user account against a real PostgreSQL migrated from zero. */
class UserAccountRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: UserAccountRepository

    private val created = Instant.parse("2026-09-30T10:00:00Z")
    private val accountId = AccountId(UUID.fromString("00000000-0000-0000-0000-00000000acc1"))
    private val hash = PasswordHash("\$argon2id\$v=19\$m=19456,t=2,p=1\$c2FsdA\$aGFzaA")
    private val account = UserAccount(accountId, hash, created, created)

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = UserAccountRepository(dsl)
    }

    @Test
    fun `there is no account before first run`() {
        repository.find() shouldBe UserAccountStoreResult.Success(null)
        repository.update(account) shouldBe UserAccountStoreResult.NotFound
    }

    @Test
    fun `the account round-trips and a second one is refused`() {
        repository.create(account) shouldBe UserAccountStoreResult.Success(Unit)

        repository.find() shouldBe UserAccountStoreResult.Success(account)
        repository.create(account.withPassword(PasswordHash("\$argon2id\$other"), created)) shouldBe
            UserAccountStoreResult.AlreadyExists
        repository.find() shouldBe UserAccountStoreResult.Success(account)
    }

    @Test
    fun `the account can be deleted once`() {
        repository.create(account)

        repository.delete() shouldBe UserAccountStoreResult.Success(Unit)
        repository.find() shouldBe UserAccountStoreResult.Success(null)
        repository.delete() shouldBe UserAccountStoreResult.NotFound
    }

    @Test
    fun `a password change keeps the creation time`() {
        repository.create(account)
        val changed = account.withPassword(PasswordHash("\$argon2id\$v=19\$new"), created.plusSeconds(90))

        repository.update(changed) shouldBe UserAccountStoreResult.Success(Unit)

        repository.find() shouldBe UserAccountStoreResult.Success(changed)
    }

    @Test
    fun `the table holds one argon2id hash at most`() {
        shouldThrow<DataAccessException> { insertRow(false, "\$argon2id\$x") }
        shouldThrow<DataAccessException> { insertRow(true, "plain text password") }
        shouldThrow<DataAccessException> { insertRow(true, "\$2a\$10\$bcrypt") }
    }

    private fun insertRow(
        singleton: Boolean,
        hash: String,
    ): Int {
        val at = created.atOffset(ZoneOffset.UTC)
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.SINGLETON, singleton)
            .set(USER_ACCOUNT.ACCOUNT_ID, UUID.randomUUID())
            .set(USER_ACCOUNT.PASSWORD_HASH, hash)
            .set(USER_ACCOUNT.CREATED_AT, at)
            .set(USER_ACCOUNT.PASSWORD_CHANGED_AT, at)
            .execute()
    }

    @Test
    fun `a broken database becomes a storage failure`() {
        val broken = UserAccountRepository(DSL.using(SQLDialect.POSTGRES))

        broken.find() shouldBe UserAccountStoreResult.StorageFailure("find")
        broken.create(account) shouldBe UserAccountStoreResult.StorageFailure("create")
        broken.update(account) shouldBe UserAccountStoreResult.StorageFailure("update")
        broken.delete() shouldBe UserAccountStoreResult.StorageFailure("delete")
    }
}
