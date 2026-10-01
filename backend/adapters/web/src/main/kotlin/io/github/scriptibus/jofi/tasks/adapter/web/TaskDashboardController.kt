// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.tasks.application.GetTaskDashboardUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** The dashboard's tasks (spec §10.1, ADR-0052), next to the countdowns of [CountdownController]. */
@RestController
@RequestMapping("/api/dashboard")
class TaskDashboardController(
    private val getTaskDashboard: GetTaskDashboardUseCase,
) {
    /**
     * The overdue open tasks and those due within the next seven days (today included), as seen on the calendar of
     * [timeZone] (the viewer's zone, e.g. `Europe/Berlin` or `+02:00`). An unknown zone is a 400 naming `timeZone`.
     */
    @GetMapping("/tasks")
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun getTaskDashboard(
        @RequestParam timeZone: String,
    ): TaskDashboardResponse {
        val zone = TaskTiming.zoneOf(timeZone) ?: throw TaskProblems.invalidViewerZone()
        return TaskDashboardResponse.from(getTaskDashboard.execute(zone).orThrow())
    }
}
