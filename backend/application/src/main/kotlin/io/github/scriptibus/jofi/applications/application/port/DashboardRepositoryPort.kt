// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.ActivityEntry
import io.github.scriptibus.jofi.applications.domain.ActivityQuery
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.PipelineOverview

/**
 * Reads the dashboard's figures of the applications context (spec §10.1, ADR-0052). Only counts, ids, codes, field
 * names and job titles leave the database: never changed values, reasons or notes. Implementations never throw and
 * never log row data.
 */
interface DashboardRepositoryPort {
    /** The applications per current status, the unread ones and the funnel over the status history. */
    fun pipeline(): ApplicationStoreResult<PipelineOverview>

    /** The newest [ActivityQuery.limit] changelog entries of [ActivityQuery.ENTITY_TYPES], newest first. */
    fun recentActivity(query: ActivityQuery): ApplicationStoreResult<List<ActivityEntry>>
}
