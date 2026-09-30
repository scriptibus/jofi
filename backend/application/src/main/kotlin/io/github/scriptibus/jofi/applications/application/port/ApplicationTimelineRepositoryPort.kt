// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.TimelineEntry
import io.github.scriptibus.jofi.applications.domain.TimelineQuery

/**
 * Reads the timeline sources the applications context owns (#87): the application's changelog entries (without
 * those a status change writes, which its history entry shows), its status history, the description snapshots of
 * its sources and its interviews. One query per source, never one per entry; free text stays in the database: a
 * change entry has values only for `TimelineEntry.Change.VALUED_FIELDS`. Implementations never throw and never log
 * row data.
 */
interface ApplicationTimelineRepositoryPort {
    /**
     * Up to [TimelineQuery.fetchSize] entries of each source after [TimelineQuery.before], each source newest
     * first; [ApplicationStoreResult.NotFound] if there is no application [id].
     */
    fun entries(
        id: ApplicationId,
        query: TimelineQuery,
    ): ApplicationStoreResult<List<TimelineEntry>>
}
