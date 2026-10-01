// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.inbound

import io.github.scriptibus.jofi.applications.domain.ActivityEntry
import io.github.scriptibus.jofi.applications.domain.ActivityQuery
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.PipelineOverview

// Inbound ports of the dashboard's application figures (#113, spec §10.1, ADR-0052), implemented by the use cases of
// the same name. Both only read.

/** The applications per status, the unread count and the funnel applied → interview → offer with the response rate. */
interface GetPipelineOverviewPort {
    fun execute(): ApplicationResult<PipelineOverview>
}

/** The newest changelog entries of the job search with their actor, ids and the application's title. */
interface ListRecentActivityPort {
    fun execute(query: ActivityQuery): ApplicationResult<List<ActivityEntry>>
}
