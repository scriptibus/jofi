// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Applications (spec §6.1). The contract only (#76): every operation answers `501 Not Implemented`
 * until the use cases land (#82 create/get/update/unread/delete, #83 list, #90 contacts), which then
 * inject them here and map each `ApplicationResult.Failure` with [ApplicationProblems.of]. Until then
 * most parameters only declare the contract, hence the suppressed unused-parameter rule.
 */
@Suppress("UnusedParameter")
@RestController
@RequestMapping("/api/applications")
class ApplicationController {
    /**
     * Applications whose title matches [search] fuzzily (best match first, otherwise newest first),
     * filtered by company and linked contact ("linked applications per contact", #90). #83 adds the
     * unread, status, score, source, language and date filters and the sort order.
     */
    @GetMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun searchApplications(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) companyId: UUID?,
        @RequestParam(required = false) contactId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${ApplicationSearch.DEFAULT_SIZE}") size: Int,
    ): ApplicationPageResponse {
        val filters =
            ApplicationSearch(
                company = companyId?.let(::CompanyRef),
                contact = contactId?.let(::ContactRef),
            )
        ApplicationSearch.of(search, filters, page, size) ?: throw ApplicationProblems.invalidSearch(page, size)
        throw notImplemented()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createApplication(
        @RequestBody request: ApplicationDetailsRequest,
    ): ApplicationResponse = throw notImplemented()

    @GetMapping("/{id}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getApplication(
        @PathVariable id: UUID,
    ): ApplicationResponse = throw notImplemented()

    /** Replaces all details (anything left out is cleared); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateApplication(
        @PathVariable id: UUID,
        @RequestBody request: UpdateApplicationRequest,
    ): ApplicationResponse = throw notImplemented()

    /** Marks the application read or unread; no version needed, the version stays. */
    @PutMapping("/{id}/unread")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun setApplicationUnread(
        @PathVariable id: UUID,
        @RequestBody request: ApplicationUnreadRequest,
    ): ApplicationResponse = throw notImplemented()

    /** Links exactly the given contacts (replacing the linked set); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}/contacts")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun linkApplicationContacts(
        @PathVariable id: UUID,
        @RequestBody request: ApplicationContactsRequest,
    ): ApplicationResponse = throw notImplemented()

    /** Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteApplication(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ): Unit = throw notImplemented()

    private fun notImplemented(): ErrorResponseException {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Applications are not available yet")
        return ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null)
    }
}
