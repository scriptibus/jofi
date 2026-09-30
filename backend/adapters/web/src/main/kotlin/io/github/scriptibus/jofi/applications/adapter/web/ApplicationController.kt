// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.CreateApplicationUseCase
import io.github.scriptibus.jofi.applications.application.DeleteApplicationUseCase
import io.github.scriptibus.jofi.applications.application.GetApplicationUseCase
import io.github.scriptibus.jofi.applications.application.SearchApplicationsUseCase
import io.github.scriptibus.jofi.applications.application.SetApplicationUnreadUseCase
import io.github.scriptibus.jofi.applications.application.UpdateApplicationUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.SearchValidation
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Applications (spec §6.1), for the logged-in user. Create, read, edit, read/unread and delete call their
 * use case (#82) and map its `ApplicationResult.Failure` with [ApplicationProblems.of]. The list (#83), the
 * status change and its history (#84) and the contact links (#90) are still the contract only and answer
 * `501 Not Implemented`; their parameters only declare it, hence the suppressed unused-parameter rule.
 */
@Suppress("UnusedParameter")
@RestController
@RequestMapping("/api/applications")
class ApplicationController(
    private val createApplication: CreateApplicationUseCase,
    private val getApplication: GetApplicationUseCase,
    private val updateApplication: UpdateApplicationUseCase,
    private val setUnread: SetApplicationUnreadUseCase,
    private val deleteApplication: DeleteApplicationUseCase,
    private val searchApplications: SearchApplicationsUseCase,
) {
    /** One page of the list with its filters and order (see [ApplicationListQuery]); 400 names bad parameters. */
    @GetMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun searchApplications(
        query: ApplicationListQuery,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${ApplicationSearch.DEFAULT_SIZE}") size: Int,
    ): ApplicationPageResponse =
        when (val validation = query.toInput(page, size).validate()) {
            is SearchValidation.Invalid -> {
                throw ApplicationProblems.invalidSearch(validation.violations)
            }

            is SearchValidation.Valid -> {
                val search = validation.search
                ApplicationPageResponse.from(searchApplications.execute(search).orThrow(), search.page, search.size)
            }
        }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createApplication(
        @RequestBody request: ApplicationDetailsRequest,
    ): ApplicationResponse =
        ApplicationResponse.from(createApplication.execute(request.toInput(), Actor.User).orThrow())

    @GetMapping("/{id}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getApplication(
        @PathVariable id: UUID,
    ): ApplicationResponse = ApplicationResponse.from(getApplication.execute(ApplicationId(id)).orThrow())

    /** Replaces all details (anything left out is cleared); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateApplication(
        @PathVariable id: UUID,
        @RequestBody request: UpdateApplicationRequest,
    ): ApplicationResponse =
        ApplicationResponse.from(
            updateApplication
                .execute(ApplicationId(id), request.details.toInput(), request.basedOnVersion, Actor.User)
                .orThrow(),
        )

    /** Marks the application read or unread; no version needed, the version stays. */
    @PutMapping("/{id}/unread")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun setApplicationUnread(
        @PathVariable id: UUID,
        @RequestBody request: ApplicationUnreadRequest,
    ): ApplicationResponse =
        ApplicationResponse.from(setUnread.execute(ApplicationId(id), request.unread, Actor.User).orThrow())

    /** Links exactly the given contacts (replacing the linked set); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}/contacts")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun linkApplicationContacts(
        @PathVariable id: UUID,
        @RequestBody request: ApplicationContactsRequest,
    ): ApplicationResponse = throw notImplemented()

    /**
     * Moves the application to another status (ADR-0044); 409 `invalid-transition` if the matrix has no
     * such move, 409 `version-conflict` if `basedOnVersion` is stale.
     */
    @PutMapping("/{id}/status")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun changeApplicationStatus(
        @PathVariable id: UUID,
        @RequestBody request: ChangeApplicationStatusRequest,
    ): ApplicationResponse = throw notImplemented()

    /** Every status change of the application, oldest first. */
    @GetMapping("/{id}/status-history")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getApplicationStatusHistory(
        @PathVariable id: UUID,
    ): StatusHistoryResponse = throw notImplemented()

    /**
     * Two steps (ADR-0039): the first call answers 428 with a token (the effect counts the contact links and
     * status changes that go with the application), the repeat with it deletes.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteApplication(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ) {
        deleteApplication
            .execute(ApplicationId(id), Confirmations.requester(request), Confirmations.token(confirmation))
            .orThrow()
    }

    private fun notImplemented(): ErrorResponseException {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Applications are not available yet")
        return ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null)
    }
}

/** The value, or the failure's problem thrown for Spring to answer. */
internal fun <T> ApplicationResult<T>.orThrow(): T =
    when (this) {
        is ApplicationResult.Success -> value
        is ApplicationResult.Failure -> throw ApplicationProblems.of(this)
    }
