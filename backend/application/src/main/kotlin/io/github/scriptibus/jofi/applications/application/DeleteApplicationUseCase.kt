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
 * Deletes an application in two steps (ADR-0039). The application, its status history and its snapshot
 * count are read in the transaction of the delete, and the confirmation effect (title, number of contact
 * links, status changes, sources and description snapshots that go with it by `ON DELETE CASCADE`) is built
 * from that read, so an edit of the title, a new link, source or snapshot or a status change between the
 * steps voids the token. After the delete it writes the changelog entry and publishes `ApplicationDeleted`.
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
                cascadeCounts(application).then { confirmThenDelete(application, it, requester, token) }
            }
        }

    /** What goes with [application] by `ON DELETE CASCADE`, counted from the reads of this transaction. */
    private fun cascadeCounts(application: Application): ApplicationResult<Map<String, Int>> =
        applications.statusHistory(application.id).toResult().then { history ->
            applications.snapshotCount(application.id).toResult().then { snapshots ->
                ApplicationResult.Success(
                    mapOf(
                        CONTACT_LINKS to application.contacts.size,
                        STATUS_CHANGES to history.size,
                        SOURCES to application.sources.size,
                        SNAPSHOTS to snapshots,
                    ),
                )
            }
        }

    private fun confirmThenDelete(
        application: Application,
        counts: Map<String, Int>,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit> {
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
        const val SOURCES = "sources"
        const val SNAPSHOTS = "snapshots"
    }
}
