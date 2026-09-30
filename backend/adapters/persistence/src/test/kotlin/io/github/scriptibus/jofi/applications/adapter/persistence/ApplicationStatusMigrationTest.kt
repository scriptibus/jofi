// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The status migration (#77) on applications stored before it (a running instance, or an older backup
 * migrated in a scratch database): every row gets a status the domain accepts and one history entry,
 * and the status-dependent constraint is validated on them.
 */
class ApplicationStatusMigrationTest {
    private val container = PostgresTestDatabase.container
    private val dsl = DSL.using(container.jdbcUrl, container.username, container.password)

    private fun flyway(target: String): Flyway =
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .cleanDisabled(false)
            .target(target)
            .load()

    @Test
    fun `rows without a status become discovered, or declined if they hold a decline reason, each with one entry`() {
        flyway(BEFORE_STATUS).clean()
        flyway(BEFORE_STATUS).migrate()
        dsl.execute(
            "insert into company (id, name, created_at, updated_at) values (?, 'ACME', ?::timestamptz, ?::timestamptz)",
            COMPANY,
            EARLIER,
            EARLIER,
        )
        insertApplication(PLAIN, "null, null", LATER)
        insertApplication(DECLINED, "'SALARY', 'Too low'", EARLIER)

        flyway("latest").migrate()

        dsl.fetch("select id, status from application order by id").map { it.get(0) to it.get(1) } shouldBe
            listOf(PLAIN to "DISCOVERED", DECLINED to "DECLINED")
        dsl
            .fetch(
                "select c.application_id, c.from_status, c.to_status, c.reason, c.decline_category, c.actor_kind, " +
                    "c.actor_name, c.changed_at = a.created_at from application_status_change c " +
                    "join application a on a.id = c.application_id order by c.id",
            ).map { it.intoList() } shouldBe
            listOf(
                listOf(DECLINED, null, "DECLINED", "Too low", "SALARY", "SYSTEM", "status-history-backfill", true),
                listOf(PLAIN, null, "DISCOVERED", null, null, "SYSTEM", "status-history-backfill", true),
            )
        dsl.fetchValue(
            "select convalidated from pg_constraint where conname = 'application_decline_reason_matches_status'",
        ) shouldBe true
    }

    private fun insertApplication(
        id: UUID,
        decline: String,
        createdAt: String,
    ) {
        dsl.execute(
            "insert into application (id, company_id, title, decline_category, decline_reason, created_at, " +
                "updated_at) values (?, ?, 'Backend', $decline, ?::timestamptz, ?::timestamptz)",
            id,
            COMPANY,
            createdAt,
            createdAt,
        )
    }

    private companion object {
        const val BEFORE_STATUS = "20260930130101"
        const val EARLIER = "2026-09-01 10:00:00+00"
        const val LATER = "2026-09-02 10:00:00+00"
        val COMPANY: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c0")
        val PLAIN: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        val DECLINED: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    }
}
