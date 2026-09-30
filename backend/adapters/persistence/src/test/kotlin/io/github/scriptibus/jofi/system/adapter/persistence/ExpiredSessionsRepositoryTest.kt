// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION_ATTRIBUTES
import io.github.scriptibus.jofi.system.domain.SessionCleanupResult
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class ExpiredSessionsRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: ExpiredSessionsRepository
    private val now = Instant.parse("2026-09-30T10:00:00Z")

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = ExpiredSessionsRepository(dsl)
    }

    private fun session(
        id: String,
        expiresAt: Instant,
    ) {
        dsl
            .insertInto(SPRING_SESSION)
            .set(SPRING_SESSION.PRIMARY_ID, id)
            .set(SPRING_SESSION.SESSION_ID, id)
            .set(SPRING_SESSION.CREATION_TIME, now.minusSeconds(3600).toEpochMilli())
            .set(SPRING_SESSION.LAST_ACCESS_TIME, now.minusSeconds(3600).toEpochMilli())
            .set(SPRING_SESSION.MAX_INACTIVE_INTERVAL, 60)
            .set(SPRING_SESSION.EXPIRY_TIME, expiresAt.toEpochMilli())
            .set(SPRING_SESSION.PRINCIPAL_NAME, "owner")
            .execute()
        dsl
            .insertInto(SPRING_SESSION_ATTRIBUTES)
            .set(SPRING_SESSION_ATTRIBUTES.SESSION_PRIMARY_ID, id)
            .set(SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_NAME, "login")
            .set(SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES, byteArrayOf(1))
            .execute()
    }

    @Test
    fun `only sessions past their idle timeout are deleted, with their attributes`() {
        session("00000000-0000-0000-0000-000000000001", now.minusSeconds(1))
        session("00000000-0000-0000-0000-000000000002", now.minusSeconds(7200))
        session("00000000-0000-0000-0000-000000000003", now.plusSeconds(60))

        repository.deleteExpired(now) shouldBe SessionCleanupResult.Cleaned(2)

        dsl.fetch(SPRING_SESSION).map { it.primaryId } shouldBe listOf("00000000-0000-0000-0000-000000000003")
        dsl.fetchCount(SPRING_SESSION_ATTRIBUTES) shouldBe 1
        repository.deleteExpired(now) shouldBe SessionCleanupResult.Cleaned(0)
    }

    @Test
    fun `a broken database becomes a storage failure`() {
        ExpiredSessionsRepository(DSL.using(SQLDialect.POSTGRES)).deleteExpired(now) shouldBe
            SessionCleanupResult.StorageFailure
    }
}
