// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SECRET
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class MasterKeyRecordRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: MasterKeyRecordRepository
    private val at = Instant.parse("2026-09-30T10:00:00Z")

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = MasterKeyRecordRepository(dsl)
    }

    @Test
    fun `the check value is recorded once and replaced`() {
        (repository.findCheckValue() as SystemStoreResult.Success).value shouldBe null

        repository.saveCheckValue(byteArrayOf(1, 2), at) shouldBe SystemStoreResult.Success(Unit)
        repository.saveCheckValue(byteArrayOf(3), at.plusSeconds(1)) shouldBe SystemStoreResult.Success(Unit)

        (repository.findCheckValue() as SystemStoreResult.Success).value?.toList() shouldBe listOf<Byte>(3)
    }

    @Test
    fun `it tells whether secrets are stored`() {
        repository.hasSecrets() shouldBe SystemStoreResult.Success(false)

        val now = at.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(SECRET)
            .set(SECRET.ID, UUID.randomUUID())
            .set(SECRET.CIPHERTEXT, byteArrayOf(9))
            .set(SECRET.CREATED_AT, now)
            .set(SECRET.UPDATED_AT, now)
            .execute()

        repository.hasSecrets() shouldBe SystemStoreResult.Success(true)
    }

    @Test
    fun `a broken database becomes a storage failure`() {
        val broken = MasterKeyRecordRepository(DSL.using(SQLDialect.POSTGRES))

        broken.findCheckValue() shouldBe SystemStoreResult.StorageFailure("findCheckValue")
        broken.saveCheckValue(byteArrayOf(1), at) shouldBe SystemStoreResult.StorageFailure("saveCheckValue")
        broken.hasSecrets() shouldBe SystemStoreResult.StorageFailure("hasSecrets")
    }
}
