// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.CreateSavedViewPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.applications.domain.SavedViewInput
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock
import java.util.UUID

/**
 * Saves filters and order of the list under a new name (spec §6.3, ADR-0050). A name another view has, ignoring
 * case, is `InvalidView` (name, TAKEN); the view and its changelog entry (the name; never the filter) are
 * stored together.
 */
class CreateSavedViewUseCase(
    private val views: SavedViewRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : CreateSavedViewPort {
    override fun execute(
        input: SavedViewInput,
        actor: Actor,
    ): ApplicationResult<SavedView> =
        input.validate().toResult().then { details ->
            transactions.inApplicationTransaction {
                views.nameFree(details).then {
                    val view = SavedView.create(SavedViewId(UUID.randomUUID()), details, clock.storedNow())
                    views.add(view).toResult().then {
                        val recorded =
                            changelog.record(
                                view.id.toEntityRef(),
                                actor,
                                view.createdAt,
                                "Created saved view",
                                nameChange(null, details.name),
                            )
                        view.applicationIf(recorded, "changelog")
                    }
                }
            }
        }
}
