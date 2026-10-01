// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.GetPipelineOverviewUseCase
import io.github.scriptibus.jofi.applications.application.ListRecentActivityUseCase
import io.github.scriptibus.jofi.applications.domain.ActivityQuery
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The dashboard's application figures (spec §10.1, ADR-0052): the pipeline and the recent activity. The tasks
 * (`/api/dashboard/tasks`) and countdowns come from the tasks context; the AI cost this month against the budget is
 * `GET /api/setup/costs`.
 */
@RestController
@RequestMapping("/api/dashboard")
class DashboardController(
    private val getPipelineOverview: GetPipelineOverviewUseCase,
    private val listRecentActivity: ListRecentActivityUseCase,
) {
    /** The applications per status, the unread ones, the funnel (applied, interview, offer) and the response rate. */
    @GetMapping("/pipeline")
    fun getPipelineOverview(): PipelineOverviewResponse =
        PipelineOverviewResponse.from(getPipelineOverview.execute().orThrow())

    /**
     * The newest `limit` (1 to 100, default 20) changelog entries of applications, companies, contacts, tasks and
     * countdowns, newest first. 400 names a limit out of range.
     */
    @GetMapping("/activity")
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun listRecentActivity(
        @RequestParam(defaultValue = "${ActivityQuery.DEFAULT_LIMIT}") limit: Int,
    ): RecentActivityResponse {
        if (limit !in 1..ActivityQuery.MAX_LIMIT) {
            throw ValidationProblem.of(
                ApplicationProblems.INVALID_ACTIVITY_QUERY,
                listOf(FieldViolation("limit", OUT_OF_RANGE)),
            )
        }
        return RecentActivityResponse.from(listRecentActivity.execute(ActivityQuery(limit)).orThrow())
    }

    private companion object {
        const val OUT_OF_RANGE = "OUT_OF_RANGE"
    }
}
