// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.UpdateSavedViewPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewDetails
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.applications.domain.SavedViewInput
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Replaces name and filter of a saved view; a rename sends the filter it read (ADR-0050). The version is checked
 * first, then the input, then the name against the other views (ignoring case). Unchanged details store and record
 * nothing, unless the view came back adjusted: then it is stored as it is now, and the filter counts as changed.
 */
class UpdateSavedViewUseCase(
    private val views: SavedViewRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : UpdateSavedViewPort {
    override fun execute(
        id: SavedViewId,
        input: SavedViewInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<SavedView> =
        transactions.inApplicationTransaction {
            views
                .findById(id)
                .savedViewResult()
                .then { it.basedOn(basedOnVersion) }
                .then { current ->
                    input
                        .validate()
                        .toResult()
                        .then { views.nameFree(it, except = id) }
                        .then { edit(current, it, actor) }
                }
        }

    private fun edit(
        current: SavedView,
        details: SavedViewDetails,
        actor: Actor,
    ): ApplicationResult<SavedView> {
        val edited = current.edit(details, clock.storedNow())
        if (edited == current) return ApplicationResult.Success(current)
        return views.update(edited).savedViewResult().then {
            val filterChanged = current.adjusted || current.details.filter != details.filter
            val recorded =
                changelog.record(
                    edited.id.toEntityRef(),
                    actor,
                    edited.updatedAt,
                    describeView("Edited saved view", filterChanged),
                    nameChange(current.details.name, details.name),
                )
            edited.applicationIf(recorded, "changelog")
        }
    }
}
