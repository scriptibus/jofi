// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.SearchApplicationsUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.SearchValidation
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The application list (spec §6.3, #83) at `GET /api/applications`, apart from [ApplicationController] so
 * neither takes more use cases than the constructor limit allows.
 */
@RestController
@RequestMapping("/api/applications")
class ApplicationListController(
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
}
