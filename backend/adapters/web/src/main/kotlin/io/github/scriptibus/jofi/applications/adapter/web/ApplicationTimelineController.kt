// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.GetApplicationTimelineUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.TimelinePosition
import io.github.scriptibus.jofi.applications.domain.TimelineQuery
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** The timeline of an application (#87), apart from [ApplicationController], which is at its constructor limit. */
@RestController
@RequestMapping("/api/applications")
class ApplicationTimelineController(
    private val timeline: GetApplicationTimelineUseCase,
) {
    /**
     * Up to `limit` (1 to 100) entries, newest first, after `cursor` (a page's `nextCursor`; the newest without).
     * 400 names a cursor this timeline did not give out or a limit out of range.
     */
    @GetMapping("/{id}/timeline")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND)
    fun getApplicationTimeline(
        @PathVariable id: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "${TimelineQuery.DEFAULT_LIMIT}") limit: Int,
    ): ApplicationTimelineResponse {
        val before = cursor?.let(TimelinePosition::parse)
        val violations =
            listOfNotNull(
                FieldViolation("cursor", INVALID_CURSOR).takeIf { cursor != null && before == null },
                FieldViolation("limit", OUT_OF_RANGE).takeIf { limit !in 1..TimelineQuery.MAX_LIMIT },
            )
        if (violations.isNotEmpty()) throw ValidationProblem.of(ApplicationProblems.INVALID_TIMELINE_QUERY, violations)
        return ApplicationTimelineResponse.from(
            timeline.execute(ApplicationId(id), TimelineQuery(before, limit)).orThrow(),
        )
    }

    private companion object {
        const val INVALID_CURSOR = "INVALID_CURSOR"
        const val OUT_OF_RANGE = "OUT_OF_RANGE"
    }
}
