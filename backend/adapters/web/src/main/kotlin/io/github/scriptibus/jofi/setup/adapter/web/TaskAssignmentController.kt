// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.AssignTaskModelUseCase
import io.github.scriptibus.jofi.setup.application.ListTaskAssignmentsUseCase
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Which model runs each AI task (spec §3.2), with a warning for every capability the model lacks. A
 * weaker model is allowed; the answer says what it is missing.
 */
@RestController
@RequestMapping("/api/setup/assignments")
class TaskAssignmentController(
    private val listAssignments: ListTaskAssignmentsUseCase,
    private val assignModel: AssignTaskModelUseCase,
) {
    /** Every task, assigned or not, in a fixed order. */
    @GetMapping
    fun listTaskAssignments(): List<TaskAssignmentResponse> =
        listAssignments.execute().orThrow().map(TaskAssignmentResponse::from)

    @PutMapping("/{task}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND)
    fun assignTaskModel(
        @PathVariable task: AiTaskType,
        @RequestBody request: AssignTaskModelRequest,
    ): TaskAssignmentResponse {
        val assigned = assignModel.execute(task.toDomain(), ProviderId(request.providerId), request.model, Actor.User)
        return TaskAssignmentResponse.from(assigned.orThrow())
    }
}
