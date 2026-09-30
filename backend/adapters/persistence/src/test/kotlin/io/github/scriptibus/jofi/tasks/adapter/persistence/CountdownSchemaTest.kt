// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COUNTDOWN
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.CountdownRecord
import io.github.scriptibus.jofi.tasks.domain.CountdownDetails
import io.github.scriptibus.jofi.tasks.domain.CountdownInput
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskValidation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** `countdown` on a real PostgreSQL (ADR-0041): named constraints mirroring `CountdownDetails`, never stricter. */
class CountdownSchemaTest {
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
    }

    @Test
    fun `every constraint has a name of its own`() {
        ApplicationRows(dsl).constraintsOf("countdown") shouldBe
            listOf(
                "countdown_pk",
                "countdown_title_valid",
                "countdown_updated_after_created",
                "countdown_version_valid",
            )
    }

    @Test
    fun `stores whatever the domain accepts, at exactly its limits`() {
        // Exactly the limit in UTF-16 units, which the domain counts (the rocket is two).
        val longest = "ü".repeat(CountdownDetails.MAX_TITLE_LENGTH - 3) + " 🚀"
        val inputs =
            listOf(
                CountdownInput(longest, TaskTiming.EARLIEST_DAY),
                CountdownInput("İstanbul, \"Kündigung\";\nEnde ☕", TaskTiming.LATEST_DAY.minusDays(1)),
            )

        inputs.forEach { input ->
            val details = input.validate().shouldBeInstanceOf<TaskValidation.Valid<CountdownDetails>>().value
            insert {
                title = details.title
                targetDate = details.targetDate
            }
        }

        dsl.fetchValues(COUNTDOWN.TARGET_DATE).toSet() shouldBe
            setOf(TaskTiming.EARLIEST_DAY, LocalDate.parse("2099-12-31"))
    }

    @Test
    fun `rejects countdowns the domain rejects`() {
        rejects("countdown_title_valid") { insert { title = "" } }
        rejects("countdown_title_valid") { insert { title = "Notice " } }
        rejects("countdown_title_valid") { insert { title = "x".repeat(CountdownDetails.MAX_TITLE_LENGTH + 1) } }
        rejects("countdown_version_valid") { insert { version = -1L } }
        rejects("countdown_updated_after_created") { insert { updatedAt = NOW.minusSeconds(1) } }
        shouldThrow<DataAccessException> { insert { title = "a\u0000b" } }
        dsl.fetchCount(COUNTDOWN) shouldBe 0
    }

    private fun insert(customize: CountdownRecord.() -> Unit) {
        dsl
            .newRecord(COUNTDOWN)
            .apply {
                id = UUID.randomUUID()
                title = "Notice ends"
                targetDate = LocalDate.parse("2026-12-31")
                createdAt = NOW
                updatedAt = NOW
                customize()
            }.insert()
    }
}
