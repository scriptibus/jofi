// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Public
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION_ATTRIBUTES
import org.jooq.Table

/**
 * Which tables a backup holds (ADR-0042). Every table of the schema is either [EXPORTED] or
 * [EXCLUDED] with a reason; `DatabaseBackupRepositoryTest` enumerates the generated jOOQ schema and
 * fails for a table in neither list, and its round-trip test fails for an exported table it cannot seed and
 * restore. A new table therefore cannot be forgotten (spec §13 portability).
 */
internal object BackupTables {
    val EXPORTED: Set<String> =
        setOf(
            "changelog_entry",
            "secret",
            "ai_provider_config",
            "ai_model_capability",
            "ai_model_price_override",
            "ai_model_assignment",
            "ai_cost_entry",
            "ai_monthly_budget",
            "user_account",
            "master_key_check",
            "company",
            "contact",
            "contact_channel",
            "application",
            "application_contact",
            "application_status_change",
            "application_source",
            "application_description_snapshot",
            "interview",
            "interview_participant",
            "task",
            "countdown",
            "saved_view",
            "application_settings",
            "posting_import",
        )

    val EXCLUDED: Map<String, String> =
        mapOf(
            "spring_session" to "login sessions are bearer credentials; a restore ends them all (ADR-0035)",
            "spring_session_attributes" to "belongs to spring_session",
            "jobrunr_jobs" to "JobRunr's job queue: operational state with ids only (ADR-0038)",
            "jobrunr_recurring_jobs" to "registered again by app at every start (ADR-0038)",
            "jobrunr_backgroundjobservers" to "heartbeats of running workers (ADR-0038)",
            "jobrunr_metadata" to "JobRunr's own counters (ADR-0038)",
            "jobrunr_jobs_stats" to "a view over the job store, no data of its own",
        )

    /** Emptied by a restore although not exported: a restore ends every login session. */
    val CLEARED_ON_RESTORE: List<Table<*>> = listOf(SPRING_SESSION_ATTRIBUTES, SPRING_SESSION)

    /** The exported tables, every table after the tables it references (the restore order). */
    val exported: List<Table<*>> by lazy { inRestoreOrder(Public.PUBLIC.tables.filter { it.name in EXPORTED }) }

    private fun inRestoreOrder(tables: List<Table<*>>): List<Table<*>> {
        val names = tables.map { it.name }.toSet()
        val ordered = mutableListOf<Table<*>>()
        val pending = tables.sortedBy { it.name }.toMutableList()
        while (pending.isNotEmpty()) {
            val placed = ordered.map { it.name }.toSet()
            val next =
                checkNotNull(pending.firstOrNull { table -> referencedBy(table, names).all { it in placed } }) {
                    "The exported tables reference each other in a cycle"
                }
            ordered += next
            pending -= next
        }
        return ordered
    }

    private fun referencedBy(
        table: Table<*>,
        names: Set<String>,
    ): Set<String> =
        table.references
            .map { it.key.table.name }
            .filter { it in names && it != table.name }
            .toSet()
}
