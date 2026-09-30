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
 * Saved views of the application list (spec §6.3, ADR-0050). The contract only (#81): every operation answers
 * `501 Not Implemented` until #99 injects the use cases and maps each `ApplicationResult.Failure` with
 * [ApplicationProblems.of].
 */
@Suppress("UnusedParameter")
@RestController
@RequestMapping("/api/applications/saved-views")
class SavedViewController {
    /** Every saved view by name. */
    @GetMapping
    fun listSavedViews(): SavedViewListResponse = throw notImplemented()

    /** Saves filters and order of the list under a new name. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createSavedView(
        @RequestBody request: SavedViewRequest,
    ): SavedViewResponse = throw notImplemented()

    @GetMapping("/{id}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getSavedView(
        @PathVariable id: UUID,
    ): SavedViewResponse = throw notImplemented()

    /** Replaces name and filter (a rename sends the filter unchanged); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateSavedView(
        @PathVariable id: UUID,
        @RequestBody request: UpdateSavedViewRequest,
    ): SavedViewResponse = throw notImplemented()

    /** Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes the view only. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteSavedView(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ): Unit = throw notImplemented()

    private fun notImplemented(): ErrorResponseException {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Saved views are not available yet")
        return ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null)
    }
}
