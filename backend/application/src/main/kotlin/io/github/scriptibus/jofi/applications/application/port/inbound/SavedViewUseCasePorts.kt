// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.inbound

import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.applications.domain.SavedViewInput
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken

// Inbound ports for saved views (#81), implemented by the use cases of the same name (#99). An unknown view is
// `SavedViewNotFound`; a name or filter the rules refuse is `InvalidView`, and so is a name another view has
// (ignoring case, `SavedView.isNamed`: NAME, TAKEN). Mutations take the acting `Actor` and write a changelog entry
// (entity `saved_view`) naming the changed fields (`name`, `filter`), never the filter's search text.
// `basedOnVersion` is the `SavedView.version` the caller last read: a stale one is `VersionConflict`, checked first.

/** Saves filters and order of the list under a new name. */
interface CreateSavedViewPort {
    fun execute(
        input: SavedViewInput,
        actor: Actor,
    ): ApplicationResult<SavedView>
}

/**
 * Replaces name and filter (a rename sends the filter it read, unchanged). An unchanged view stores nothing and
 * writes no changelog entry, unless it came back `adjusted`: then it is stored as it is now.
 */
interface UpdateSavedViewPort {
    fun execute(
        id: SavedViewId,
        input: SavedViewInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<SavedView>
}

/** One saved view, to open it. Reads only. */
interface GetSavedViewPort {
    fun execute(id: SavedViewId): ApplicationResult<SavedView>
}

/** Every saved view by name. Reads only. */
interface ListSavedViewsPort {
    fun execute(): ApplicationResult<List<SavedView>>
}

/**
 * Deletes a saved view in two steps (ADR-0039): the token is bound to [SavedView.DELETE_OPERATION], the id and the
 * effect `ConfirmationEffect("saved_view", <name>)`. The applications it lists stay. The actor is [requester]'s.
 */
interface DeleteSavedViewPort {
    fun execute(
        id: SavedViewId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit>
}
