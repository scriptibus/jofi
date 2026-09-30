// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.application.CreateCompanyUseCase
import io.github.scriptibus.jofi.companies.application.DeleteCompanyUseCase
import io.github.scriptibus.jofi.companies.application.GetCompanyUseCase
import io.github.scriptibus.jofi.companies.application.SearchCompaniesUseCase
import io.github.scriptibus.jofi.companies.application.SetCompanyPreferenceUseCase
import io.github.scriptibus.jofi.companies.application.UpdateCompanyUseCase
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySearch
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
 * Companies (spec §5), for the logged-in user. Each handler calls one use case and maps its
 * `CompanyResult.Failure` with [CompanyProblems.of]; [ProblemResponses] declares those answers.
 */
@RestController
@RequestMapping("/api/companies")
class CompanyController(
    private val searchCompanies: SearchCompaniesUseCase,
    private val createCompany: CreateCompanyUseCase,
    private val getCompany: GetCompanyUseCase,
    private val updateCompany: UpdateCompanyUseCase,
    private val setPreference: SetCompanyPreferenceUseCase,
    private val deleteCompany: DeleteCompanyUseCase,
) {
    /** Companies whose name matches [search] fuzzily (best match first, otherwise by name). */
    @GetMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun searchCompanies(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) preference: CompanyPreferenceKind?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${CompanySearch.DEFAULT_SIZE}") size: Int,
    ): CompanyPageResponse {
        val query =
            CompanySearch.of(search, preference?.toDomain(), page, size)
                ?: throw CompanyProblems.invalidSearch(page, size)
        return CompanyPageResponse.from(searchCompanies.execute(query).orThrow(), page, size)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createCompany(
        @RequestBody request: CompanyDetailsRequest,
    ): CompanyResponse = CompanyResponse.from(createCompany.execute(request.toInput(), Actor.User).orThrow())

    @GetMapping("/{id}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getCompany(
        @PathVariable id: UUID,
    ): CompanyResponse = CompanyResponse.from(getCompany.execute(CompanyId(id)).orThrow())

    /** Replaces all details (a field left out is cleared); 409 if [UpdateCompanyRequest.basedOnVersion] is stale. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateCompany(
        @PathVariable id: UUID,
        @RequestBody request: UpdateCompanyRequest,
    ): CompanyResponse =
        CompanyResponse.from(
            updateCompany
                .execute(CompanyId(id), request.details.toInput(), request.basedOnVersion, Actor.User)
                .orThrow(),
        )

    @PutMapping("/{id}/preference")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun setCompanyPreference(
        @PathVariable id: UUID,
        @RequestBody request: CompanyPreferenceRequest,
    ): CompanyResponse =
        CompanyResponse.from(
            setPreference.execute(CompanyId(id), request.toInput(), request.basedOnVersion, Actor.User).orThrow(),
        )

    /**
     * Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes the
     * company and its contacts. 409 `has-applications` while applications refer to it.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun deleteCompany(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ) {
        deleteCompany
            .execute(CompanyId(id), Confirmations.requester(request), Confirmations.token(confirmation))
            .orThrow()
    }
}

/** The value, or the failure's problem thrown for Spring to answer. */
internal fun <T> CompanyResult<T>.orThrow(): T =
    when (this) {
        is CompanyResult.Success -> value
        is CompanyResult.Failure -> throw CompanyProblems.of(this)
    }
