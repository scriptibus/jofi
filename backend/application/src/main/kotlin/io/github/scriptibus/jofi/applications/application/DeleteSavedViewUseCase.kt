// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.DeleteSavedViewPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import java.time.Clock

/**
 * Deletes a saved view in two steps (ADR-0039). The view is read in the transaction of the delete and the effect
 * (its name) is built from that read, so a rename between the steps voids the token. Nothing goes with it: the
 * applications it lists stay. The changelog entry names the view.
 */
class DeleteSavedViewUseCase(
    private val views: SavedViewRepositoryPort,
    private val confirmation: ConfirmActionUseCase,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : DeleteSavedViewPort {
    override fun execute(
        id: SavedViewId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit> =
        transactions.inApplicationTransaction {
            views.findById(id).savedViewResult().then { view -> confirmThenDelete(view, requester, token) }
        }

    private fun confirmThenDelete(
        view: SavedView,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit> {
        val effect = ConfirmationEffect(SavedViewId.ENTITY_TYPE, view.details.name)
        val action = ConfirmableAction(SavedView.DELETE_OPERATION, listOf(view.id.value.toString()), effect)
        return when (val outcome = confirmation.execute(ConfirmationRequest(requester, action, token))) {
            is ConfirmationResult.Confirmed -> {
                views.delete(view.id, outcome).savedViewResult().then {
                    val recorded =
                        changelog.record(
                            view.id.toEntityRef(),
                            requester.actor,
                            clock.storedNow(),
                            "Deleted saved view",
                            nameChange(view.details.name, null),
                        )
                    Unit.applicationIf(recorded, "changelog")
                }
            }

            is ConfirmationResult.Unconfirmed -> {
                ApplicationResult.Unconfirmed(outcome)
            }
        }
    }
}
