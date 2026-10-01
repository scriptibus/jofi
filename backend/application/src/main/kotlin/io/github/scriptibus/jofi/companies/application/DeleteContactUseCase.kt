// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.DeleteContactPort
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactDeleted
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactLinks
import io.github.scriptibus.jofi.companies.domain.ContactResult
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
 * Deletes a contact with all its personal data in two steps (ADR-0039). Contact, linked
 * applications, interviews it takes part in and linked tasks ([FindContactLinksUseCase]) are read
 * in the transaction of the delete, and the confirmation effect (name, number of linked
 * applications, interviews and tasks) is built from that read, so a rename, a new link or a new
 * participation between the steps voids the token. The delete removes the contact row; its
 * channels, application links and interview participations go by `ON DELETE CASCADE`, task links
 * by `ON DELETE SET NULL`. The changelog keeps ids only: one entry for the contact, one per
 * application it was linked to, one per interview it took part in and one per task whose link it
 * cleared (ADR-0049). `ContactDeleted` tells the other contexts.
 */
class DeleteContactUseCase(
    private val contacts: ContactRepositoryPort,
    private val links: FindContactLinksUseCase,
    private val confirmation: ConfirmActionUseCase,
    private val events: DomainEventPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : DeleteContactPort {
    override fun execute(
        id: ContactId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ContactResult<Unit> =
        transactions.inContactTransaction {
            contacts
                .findById(id)
                .toResult()
                .then { contact -> links.execute(id).then { confirmThenDelete(contact, it, requester, token) } }
        }

    private fun confirmThenDelete(
        contact: Contact,
        linked: ContactLinks,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ContactResult<Unit> {
        val counts =
            mapOf(
                APPLICATIONS to linked.applications.size,
                INTERVIEWS to linked.interviews.size,
                TASKS to linked.tasks.size,
            )
        val effect = ConfirmationEffect(ContactId.ENTITY_TYPE, contact.details.name, counts)
        val action = ConfirmableAction(Contact.DELETE_OPERATION, listOf(contact.id.value.toString()), effect)
        return when (val outcome = confirmation.execute(ConfirmationRequest(requester, action, token))) {
            is ConfirmationResult.Confirmed -> {
                contacts.delete(contact.id, outcome).toResult().then { record(contact.id, linked, requester.actor) }
            }

            is ConfirmationResult.Unconfirmed -> {
                ContactResult.Unconfirmed(outcome)
            }
        }
    }

    private fun record(
        contact: ContactId,
        linked: ContactLinks,
        actor: Actor,
    ): ContactResult<Unit> {
        val at = clock.storedNow()
        // The contact's id is all that remains of it; its name and details are not repeated anywhere.
        val unlinked = listOf(FieldChange("contacts", contact.value.toString(), null))
        val removed = listOf(FieldChange("participants", contact.value.toString(), null))
        val recorded =
            changelog.record(contact.toEntityRef(), actor, at, "Deleted contact") &&
                linked.applications.all { application ->
                    changelog.record(application, actor, at, "Unlinked a deleted contact", unlinked)
                } &&
                linked.interviews.all { interview ->
                    changelog.record(interview, actor, at, "Removed a deleted contact from the participants", removed)
                } &&
                linked.tasks.all { task -> changelog.recordClearedLink(task, contact.toEntityRef(), actor, at) }
        return Unit
            .contactIf(recorded, "changelog")
            .then { Unit.contactIf(events.publish(ContactDeleted(contact, actor, at)), "publish event") }
    }

    private companion object {
        /** The effect's count of applications the contact is unlinked from (ADR-0041); the UI names it. */
        const val APPLICATIONS = "applications"

        /** The effect's count of interviews the contact is removed from as a participant (ADR-0048). */
        const val INTERVIEWS = "interviews"

        /** The effect's count of tasks whose link to the contact the delete clears (ADR-0049). */
        const val TASKS = "tasks"
    }
}
