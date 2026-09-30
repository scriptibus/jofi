// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.NOW
import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SETTINGS
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationSettingsRecord
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** `application_settings` on a real PostgreSQL (ADR-0050): at most one row, bounds exactly the domain's. */
class ApplicationSettingsSchemaTest {
    private lateinit var dsl: DSLContext

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
    }

    @Test
    fun `every constraint has a name of its own`() {
        ApplicationRows(dsl).constraintsOf("application_settings") shouldBe
            listOf(
                "application_settings_follow_up_after_days_valid",
                "application_settings_ghosted_after_weeks_valid",
                "application_settings_pk",
                "application_settings_singleton",
                "application_settings_version_valid",
            )
    }

    @Test
    fun `no row after the migration, so the defaults apply`() {
        dsl.fetchCount(APPLICATION_SETTINGS) shouldBe 0
    }

    @Test
    fun `stores every value the domain accepts, at exactly its bounds`() {
        val bounds = ApplicationSettings.GHOSTED_WEEKS
        val days = ApplicationSettings.FOLLOW_UP_DAYS
        listOf(bounds.first to days.first, bounds.last to days.last).forEach { (weeks, followUp) ->
            dsl.deleteFrom(APPLICATION_SETTINGS).execute()
            insert {
                ghostedAfterWeeks = weeks
                followUpAfterDays = followUp
            }
            dsl.fetchSingle(APPLICATION_SETTINGS).ghostedAfterWeeks shouldBe weeks
        }
    }

    @Test
    fun `rejects values the domain rejects and a second row`() {
        rejects("application_settings_ghosted_after_weeks_valid") { insert { ghostedAfterWeeks = 0 } }
        rejects("application_settings_ghosted_after_weeks_valid") { insert { ghostedAfterWeeks = 53 } }
        rejects("application_settings_follow_up_after_days_valid") { insert { followUpAfterDays = 0 } }
        rejects("application_settings_follow_up_after_days_valid") { insert { followUpAfterDays = 91 } }
        rejects("application_settings_version_valid") { insert { version = -1L } }
        rejects("application_settings_singleton") { insert { singleton = false } }
        insert {}
        rejects("application_settings_pk") { insert {} }
    }

    private fun insert(customize: ApplicationSettingsRecord.() -> Unit) {
        dsl
            .newRecord(APPLICATION_SETTINGS)
            .apply {
                singleton = true
                ghostedAfterWeeks = 14
                followUpAfterDays = 14
                version = 1L
                updatedAt = NOW
                customize()
            }.insert()
    }
}
