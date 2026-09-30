// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.application.CreateContactUseCase
import io.github.scriptibus.jofi.companies.application.DeleteContactUseCase
import io.github.scriptibus.jofi.companies.application.GetContactUseCase
import io.github.scriptibus.jofi.companies.application.SearchContactsUseCase
import io.github.scriptibus.jofi.companies.application.UpdateContactUseCase
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactSearch
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Contact persons (spec §5), part of the companies context, for the logged-in user. Each handler calls
 * one use case and maps its `ContactResult.Failure` with [ContactProblems.of]; [ProblemResponses]
 * declares those answers.
 */
@RestController
@RequestMapping("/api/contacts")
class ContactController(
    private val searchContacts: SearchContactsUseCase,
    private val createContact: CreateContactUseCase,
    private val getContact: GetContactUseCase,
    private val updateContact: UpdateContactUseCase,
    private val deleteContact: DeleteContactUseCase,
) {
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
        val query =
            ContactSearch.of(search, companyId?.let(::CompanyId), page, size)
                ?: throw ContactProblems.invalidSearch(page, size)
        return ContactPageResponse.from(searchContacts.execute(query).orThrow(), page, size)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createContact(
        @RequestBody request: ContactDetailsRequest,
    ): ContactResponse = ContactResponse.from(createContact.execute(request.toInput(), Actor.User).orThrow())

    @GetMapping("/{id}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getContact(
        @PathVariable id: UUID,
    ): ContactResponse = ContactResponse.from(getContact.execute(ContactId(id)).orThrow())

    /** Replaces all details and channels (anything left out is removed); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateContact(
        @PathVariable id: UUID,
        @RequestBody request: UpdateContactRequest,
    ): ContactResponse =
        ContactResponse.from(
            updateContact
                .execute(ContactId(id), request.details.toInput(), request.basedOnVersion, Actor.User)
                .orThrow(),
        )

    /**
     * Two steps (ADR-0039): the first call answers 428 with a token (the effect counts the linked
     * applications), the repeat with it deletes the contact with all its personal data (spec §13).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteContact(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ) {
        deleteContact
            .execute(ContactId(id), Confirmations.requester(request), Confirmations.token(confirmation))
            .orThrow()
    }
}

/** The value, or the failure's problem thrown for Spring to answer. */
internal fun <T> ContactResult<T>.orThrow(): T =
    when (this) {
        is ContactResult.Success -> value
        is ContactResult.Failure -> throw ContactProblems.of(this)
    }
