// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.jobrunr.storage.sql.common.DatabaseCreator
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider
import org.jooq.CloseableDSLContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * Our Flyway migration creates exactly the job store JobRunr's own migrations would (ADR-0038), so
 * JobRunr can run with table creation and validation switched off. After a JobRunr upgrade that adds a
 * migration this fails: add a Flyway migration with the difference.
 */
class JobRunrSchemaTest {
    private val container = PostgresTestDatabase.container

    private fun referenceDatabase(): CloseableDSLContext {
        DSL.using(container.jdbcUrl, container.username, container.password).use {
            it.execute("DROP DATABASE IF EXISTS jobrunr_reference")
            it.execute("CREATE DATABASE jobrunr_reference")
        }
        val url = container.jdbcUrl.replaceAfterLast("/", "jobrunr_reference")
        val dataSource = DriverManagerDataSource(url, container.username, container.password)
        DatabaseCreator(dataSource, PostgresStorageProvider::class.java).runMigrations()
        return DSL.using(url, container.username, container.password)
    }

    private fun DSLContext.describe(): List<String> =
        fetch(
            """
            SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type
                   || coalesce('(' || character_maximum_length || ')', '')
                   || coalesce('(' || numeric_precision || ',' || numeric_scale || ')', '')
                   || ' nullable=' || is_nullable || ' default=' || coalesce(column_default, '-')
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name LIKE 'jobrunr%' AND table_name <> 'jobrunr_migrations'
            UNION ALL
            SELECT 'index ' || indexdef FROM pg_indexes
            WHERE schemaname = 'public' AND tablename LIKE 'jobrunr%' AND tablename <> 'jobrunr_migrations'
            UNION ALL
            SELECT 'view ' || pg_get_viewdef('jobrunr_jobs_stats'::regclass, true)
            UNION ALL
            SELECT 'metadata ' || id || ' ' || name || ' ' || owner || ' ' || trim(value) FROM jobrunr_metadata
            ORDER BY 1
            """.trimIndent(),
        ).map { it.get(0).toString() }

    @Test
    fun `the Flyway migration matches the schema of JobRunr's own migrations`() {
        val ours = PostgresTestDatabase.migratedFromZero().describe()
        val jobRunrs = referenceDatabase().use { it.describe() }

        ours shouldBe jobRunrs
        ours shouldContainAll
            listOf(
                "index CREATE INDEX jobrunr_jobs_state_updated_idx ON public.jobrunr_jobs " +
                    "USING btree (state, updatedat)",
                "metadata succeeded-jobs-counter-cluster succeeded-jobs-counter cluster 0",
            )
    }
}
