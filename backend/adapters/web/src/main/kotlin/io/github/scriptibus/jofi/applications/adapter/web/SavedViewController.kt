// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.CreateSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.DeleteSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.GetSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.ListSavedViewsUseCase
import io.github.scriptibus.jofi.applications.application.UpdateSavedViewUseCase
import io.github.scriptibus.jofi.applications.domain.SavedViewId
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
 * Saved views of the application list (spec §6.3, ADR-0050), for the logged-in user. Every operation calls its use
 * case (#99) as `Actor.User` and maps each `ApplicationResult.Failure` with [ApplicationProblems.of].
 */
@RestController
@RequestMapping("/api/applications/saved-views")
class SavedViewController(
    private val listViews: ListSavedViewsUseCase,
    private val createView: CreateSavedViewUseCase,
    private val getView: GetSavedViewUseCase,
    private val updateView: UpdateSavedViewUseCase,
    private val deleteView: DeleteSavedViewUseCase,
) {
    /** Every saved view by name. */
    @GetMapping
    fun listSavedViews(): SavedViewListResponse = SavedViewListResponse.from(listViews.execute().orThrow())

    /** Saves filters and order of the list under a new name. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ProblemResponses(ProblemKind.INVALID_INPUT)
    fun createSavedView(
        @RequestBody request: SavedViewRequest,
    ): SavedViewResponse = SavedViewResponse.from(createView.execute(request.toInput(), Actor.User).orThrow())

    @GetMapping("/{id}")
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun getSavedView(
        @PathVariable id: UUID,
    ): SavedViewResponse = SavedViewResponse.from(getView.execute(SavedViewId(id)).orThrow())

    /** Replaces name and filter (a rename sends the filter unchanged); 409 if `basedOnVersion` is stale. */
    @PutMapping("/{id}")
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.NOT_FOUND, ProblemKind.CONFLICT)
    fun updateSavedView(
        @PathVariable id: UUID,
        @RequestBody request: UpdateSavedViewRequest,
    ): SavedViewResponse =
        SavedViewResponse.from(
            updateView.execute(SavedViewId(id), request.view.toInput(), request.basedOnVersion, Actor.User).orThrow(),
        )

    /** Two steps (ADR-0039): the first call answers 428 with a token, the repeat with it deletes the view only. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ProblemResponses(ProblemKind.NOT_FOUND)
    fun deleteSavedView(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        request: HttpServletRequest,
    ) {
        deleteView
            .execute(SavedViewId(id), Confirmations.requester(request), Confirmations.token(confirmation))
            .orThrow()
    }
}
