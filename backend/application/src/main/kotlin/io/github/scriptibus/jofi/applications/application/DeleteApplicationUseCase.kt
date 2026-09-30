// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.DeleteApplicationPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDeleted
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import java.time.Clock

/**
 * Deletes an application in two steps (ADR-0039). The application and its status history are read in the
 * transaction of the delete, and the confirmation effect (title, number of contact links and status
 * changes that go with it by `ON DELETE CASCADE`) is built from that read, so an edit of the title, a new
 * link or a status change between the steps voids the token. After the delete it writes the changelog
 * entry and publishes `ApplicationDeleted`.
 */
class DeleteApplicationUseCase(
    private val applications: ApplicationRepositoryPort,
    private val confirmation: ConfirmActionUseCase,
    private val events: DomainEventPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : DeleteApplicationPort {
    override fun execute(
        id: ApplicationId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit> =
        transactions.inApplicationTransaction {
            applications.findById(id).toResult().then { application ->
                applications.statusHistory(id).toResult().then { history ->
                    confirmThenDelete(application, history.size, requester, token)
                }
            }
        }

    private fun confirmThenDelete(
        application: Application,
        statusChanges: Int,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit> {
        val counts = mapOf(CONTACT_LINKS to application.contacts.size, STATUS_CHANGES to statusChanges)
        val effect = ConfirmationEffect(ApplicationId.ENTITY_TYPE, application.details.title, counts)
        val action = ConfirmableAction(Application.DELETE_OPERATION, listOf(application.id.value.toString()), effect)
        return when (val outcome = confirmation.execute(ConfirmationRequest(requester, action, token))) {
            is ConfirmationResult.Confirmed -> {
                applications.delete(application.id, outcome).toResult().then { record(application, requester.actor) }
            }

            is ConfirmationResult.Unconfirmed -> {
                ApplicationResult.Unconfirmed(outcome)
            }
        }
    }

    private fun record(
        application: Application,
        actor: Actor,
    ): ApplicationResult<Unit> {
        val at = clock.storedNow()
        val title = listOf(FieldChange("title", application.details.title, null))
        val recorded = changelog.record(application.id.toEntityRef(), actor, at, "Deleted application", title)
        return Unit
            .applicationIf(recorded, "changelog")
            .then { Unit.applicationIf(events.publish(ApplicationDeleted(application.id, actor, at)), "publish event") }
    }

    private companion object {
        // The effect's counts of what goes with the application (ADR-0041); the UI names them.
        const val CONTACT_LINKS = "contactLinks"
        const val STATUS_CHANGES = "statusChanges"
    }
}
