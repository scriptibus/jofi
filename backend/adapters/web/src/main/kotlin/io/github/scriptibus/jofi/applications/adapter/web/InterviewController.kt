// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.DeleteInterviewUseCase
import io.github.scriptibus.jofi.applications.application.GetInterviewUseCase
import io.github.scriptibus.jofi.applications.application.ListInterviewsUseCase
import io.github.scriptibus.jofi.applications.application.ListUpcomingInterviewsUseCase
import io.github.scriptibus.jofi.applications.application.LogInterviewUseCase
import io.github.scriptibus.jofi.applications.application.UpdateInterviewUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Interviews and calls of an application (spec §6.1, ADR-0048), for the logged-in user. Log, edit, read, list and
 * delete (#91) and the list of upcoming ones across all applications (#92) call their use case and map its
 * `ApplicationResult.Failure` with [ApplicationProblems.of]. None of them changes the application's version.
 */
@RestController
@RequestMapping("/api")
class InterviewController(
    private val logInterview: LogInterviewUseCase,
    private val updateInterview: UpdateInterviewUseCase,
    private val getInterview: GetInterviewUseCase,
    private val listInterviews: ListInterviewsUseCase,
    private val deleteInterview: DeleteInterviewUseCase,
    private val listUpcomingInterviews: ListUpcomingInterviewsUseCase,
) {
    /** The application's interviews and calls in the order they start. */
    @GetMapping("/applications/{id}/interviews")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun listInterviews(
        @PathVariable id: UUID,
    ): InterviewListResponse = InterviewListResponse.from(listInterviews.execute(ApplicationId(id)).orThrow())

    /** Logs an interview or call, before or after it took place. */
    @PostMapping("/applications/{id}/interviews")
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND)
    fun logInterview(
        @PathVariable id: UUID,
        @RequestBody request: InterviewRequest,
    ): InterviewResponse =
        InterviewResponse.from(logInterview.execute(ApplicationId(id), request.toInput(), Actor.User).orThrow())

    @GetMapping("/applications/{id}/interviews/{interviewId}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getInterview(
        @PathVariable id: UUID,
        @PathVariable interviewId: UUID,
    ): InterviewResponse =
        InterviewResponse.from(getInterview.execute(ApplicationId(id), InterviewId(interviewId)).orThrow())

    /** Replaces all details (anything left out is cleared); 409 if `basedOnVersion` is stale. */
    @PutMapping("/applications/{id}/interviews/{interviewId}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateInterview(
        @PathVariable id: UUID,
        @PathVariable interviewId: UUID,
        @RequestBody request: UpdateInterviewRequest,
    ): InterviewResponse =
        InterviewResponse.from(
            updateInterview
                .execute(
                    ApplicationId(id),
                    InterviewId(interviewId),
                    request.details.toInput(),
                    request.basedOnVersion,
                    Actor.User,
                ).orThrow(),
        )

    /** Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes. */
    @DeleteMapping("/applications/{id}/interviews/{interviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteInterview(
        @PathVariable id: UUID,
        @PathVariable interviewId: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ) {
        deleteInterview
            .execute(
                ApplicationId(id),
                InterviewId(interviewId),
                Confirmations.requester(request),
                Confirmations.token(confirmation),
            ).orThrow()
    }

    /** The interviews and calls still to come across all applications, soonest first (at most 100), not cancelled. */
    @GetMapping("/interviews/upcoming")
    fun listUpcomingInterviews(): UpcomingInterviewListResponse =
        UpcomingInterviewListResponse.from(listUpcomingInterviews.execute().orThrow())
}
