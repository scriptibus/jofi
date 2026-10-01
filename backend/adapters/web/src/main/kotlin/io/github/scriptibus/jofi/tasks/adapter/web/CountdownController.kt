// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.CreateCountdownUseCase
import io.github.scriptibus.jofi.tasks.application.DeleteCountdownUseCase
import io.github.scriptibus.jofi.tasks.application.ListCountdownsUseCase
import io.github.scriptibus.jofi.tasks.application.ListDashboardCountdownsUseCase
import io.github.scriptibus.jofi.tasks.application.UpdateCountdownUseCase
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Countdowns for the dashboard (spec §10.1, #112), for the logged-in user: the custom ones (create, edit, delete in two
 * steps, list) as `Actor.User`, and the dashboard query over custom and derived countdowns. Each `TaskResult.Failure`
 * is mapped with [TaskProblems.of].
 */
@RestController
@RequestMapping("/api")
class CountdownController(
    private val createCountdown: CreateCountdownUseCase,
    private val updateCountdown: UpdateCountdownUseCase,
    private val deleteCountdown: DeleteCountdownUseCase,
    private val listCountdowns: ListCountdownsUseCase,
    private val listDashboardCountdowns: ListDashboardCountdownsUseCase,
) {
    /** The custom countdowns, soonest target first, past ones included. */
    @GetMapping("/countdowns")
    fun listCountdowns(): CountdownListResponse = CountdownListResponse.from(listCountdowns.execute().orThrow())

    @PostMapping("/countdowns")
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createCountdown(
        @RequestBody request: CountdownRequest,
    ): CountdownResponse = CountdownResponse.from(createCountdown.execute(request.toInput(), Actor.User).orThrow())

    /** Replaces title and target date; 409 if `basedOnVersion` is stale. */
    @PutMapping("/countdowns/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateCountdown(
        @PathVariable id: UUID,
        @RequestBody request: UpdateCountdownRequest,
    ): CountdownResponse =
        CountdownResponse.from(
            updateCountdown
                .execute(CountdownId(id), request.details.toInput(), request.basedOnVersion, Actor.User)
                .orThrow(),
        )

    /** Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes. */
    @DeleteMapping("/countdowns/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteCountdown(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ) {
        deleteCountdown
            .execute(CountdownId(id), Confirmations.requester(request), Confirmations.token(confirmation))
            .orThrow()
    }

    /**
     * Every countdown of the dashboard, soonest first: custom ones, the next interview, application deadlines and
     * offer answer deadlines from today on, as seen on the calendar of [timeZone] (the viewer's zone). An unknown zone
     * is a 400 naming `timeZone`.
     */
    @GetMapping("/dashboard/countdowns")
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun listDashboardCountdowns(
        @RequestParam timeZone: String,
    ): DashboardCountdownListResponse {
        val zone = TaskTiming.zoneOf(timeZone) ?: throw TaskProblems.invalidViewerZone()
        return DashboardCountdownListResponse.from(listDashboardCountdowns.execute(zone).orThrow())
    }
}
