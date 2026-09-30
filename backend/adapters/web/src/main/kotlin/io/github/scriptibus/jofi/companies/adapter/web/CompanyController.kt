// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
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
 * Companies (spec §5). The contract only (#73): every operation answers `501 Not Implemented` until
 * the use cases land (#88), which then inject the use cases here and map their `CompanyResult`.
 * Until then the parameters only declare the contract, hence the suppressed unused-parameter rule.
 */
@Suppress("UnusedParameter")
@RestController
@RequestMapping("/api/companies")
class CompanyController {
    /** Companies whose name matches [search] fuzzily (best match first, otherwise by name). */
    @GetMapping
    fun searchCompanies(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) preference: CompanyPreferenceKind?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${CompanySearch.DEFAULT_SIZE}") size: Int,
    ): CompanyPageResponse = throw notImplemented()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createCompany(
        @RequestBody request: CompanyDetailsRequest,
    ): CompanyResponse = throw notImplemented()

    @GetMapping("/{id}")
    fun getCompany(
        @PathVariable id: UUID,
    ): CompanyResponse = throw notImplemented()

    @PutMapping("/{id}")
    fun updateCompany(
        @PathVariable id: UUID,
        @RequestBody request: UpdateCompanyRequest,
    ): CompanyResponse = throw notImplemented()

    @PutMapping("/{id}/preference")
    fun setCompanyPreference(
        @PathVariable id: UUID,
        @RequestBody request: CompanyPreferenceRequest,
    ): CompanyResponse = throw notImplemented()

    /** Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCompany(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ): Unit = throw notImplemented()

    private fun notImplemented(): ErrorResponseException {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Companies are not available yet")
        return ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null)
    }
}
