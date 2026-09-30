// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
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
 * Interviews and calls of an application (spec §6.1, ADR-0048). The contract only (#79): every operation answers
 * `501 Not Implemented` until #91 (log, edit, read, list, delete) and #92 (upcoming) inject their use cases and map
 * each `ApplicationResult.Failure` with [ApplicationProblems.of]. None of them changes the application's version.
 */
@Suppress("UnusedParameter")
@RestController
@RequestMapping("/api")
class InterviewController {
    /** The application's interviews and calls in the order they start. */
    @GetMapping("/applications/{id}/interviews")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun listInterviews(
        @PathVariable id: UUID,
    ): InterviewListResponse = throw notImplemented()

    /** Logs an interview or call, before or after it took place. */
    @PostMapping("/applications/{id}/interviews")
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND)
    fun logInterview(
        @PathVariable id: UUID,
        @RequestBody request: InterviewRequest,
    ): InterviewResponse = throw notImplemented()

    @GetMapping("/applications/{id}/interviews/{interviewId}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getInterview(
        @PathVariable id: UUID,
        @PathVariable interviewId: UUID,
    ): InterviewResponse = throw notImplemented()

    /** Replaces all details (anything left out is cleared); 409 if `basedOnVersion` is stale. */
    @PutMapping("/applications/{id}/interviews/{interviewId}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateInterview(
        @PathVariable id: UUID,
        @PathVariable interviewId: UUID,
        @RequestBody request: UpdateInterviewRequest,
    ): InterviewResponse = throw notImplemented()

    /** Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes. */
    @DeleteMapping("/applications/{id}/interviews/{interviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteInterview(
        @PathVariable id: UUID,
        @PathVariable interviewId: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ): Unit = throw notImplemented()

    /** The interviews and calls still to come across all applications, soonest first (at most 100). */
    @GetMapping("/interviews/upcoming")
    fun listUpcomingInterviews(): UpcomingInterviewListResponse = throw notImplemented()

    private fun notImplemented(): ErrorResponseException {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Interviews are not available yet")
        return ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null)
    }
}
