// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.ContactSearch
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
 * Contact persons (spec §5), part of the companies context. The contract only (#74): every operation
 * answers `501 Not Implemented` until the use cases land (#89), which then inject them here and map
 * each `ContactResult.Failure` with [ContactProblems.of]. Until then most parameters only declare the
 * contract, hence the suppressed unused-parameter rule.
 */
@Suppress("UnusedParameter")
@RestController
@RequestMapping("/api/contacts")
class ContactController {
    /**
     * Contacts whose name matches [search] fuzzily (best match first, otherwise by name), of the
     * company [companyId] if given.
     */
    @GetMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun searchContacts(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) companyId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${ContactSearch.DEFAULT_SIZE}") size: Int,
    ): ContactPageResponse {
        ContactSearch.of(search, companyId?.let(::CompanyId), page, size)
            ?: throw ContactProblems.invalidSearch(page, size)
        throw notImplemented()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createContact(
        @RequestBody request: ContactDetailsRequest,
    ): ContactResponse = throw notImplemented()

    @GetMapping("/{id}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getContact(
        @PathVariable id: UUID,
    ): ContactResponse = throw notImplemented()

    /** Replaces all details and channels (anything left out is removed); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateContact(
        @PathVariable id: UUID,
        @RequestBody request: UpdateContactRequest,
    ): ContactResponse = throw notImplemented()

    /**
     * Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes the
     * contact with all its personal data (spec §13).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteContact(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ): Unit = throw notImplemented()

    private fun notImplemented(): ErrorResponseException {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Contacts are not available yet")
        return ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null)
    }
}
