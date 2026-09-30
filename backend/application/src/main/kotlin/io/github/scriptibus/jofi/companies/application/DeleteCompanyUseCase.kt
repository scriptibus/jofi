// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.inbound.DeleteCompanyPort
import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.ContactDeleted
import io.github.scriptibus.jofi.companies.domain.ContactId
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
 * Deletes a company and its contacts in two steps (ADR-0039). Company, application count and contact
 * ids are read in the transaction of the delete, and the confirmation effect (name, number of
 * contacts) is built from that read, so a rename or a new contact between the steps voids the token.
 * A company with applications is refused before a token is issued. Each contact deleted with it gets a
 * changelog entry of its own (ids only) and a `ContactDeleted` event.
 */
class DeleteCompanyUseCase(
    private val companies: CompanyRepositoryPort,
    private val applications: ApplicationCountsPort,
    private val confirmation: ConfirmActionUseCase,
    private val events: DomainEventPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : DeleteCompanyPort {
    override fun execute(
        id: CompanyId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): CompanyResult<Unit> =
        transactions.whenSuccessful {
            companies
                .findById(id)
                .toResult()
                .then(::withoutApplications)
                .then { company ->
                    companies.findContactIds(id).toResult().then { confirmThenDelete(company, it, requester, token) }
                }
        }

    private fun withoutApplications(company: Company): CompanyResult<Company> =
        when (val counts = applications.countByCompany(setOf(company.id.value))) {
            is ApplicationCountsPort.Counts.Counted -> {
                if (counts.of(company.id.value) == 0) CompanyResult.Success(company) else CompanyResult.HasApplications
            }

            ApplicationCountsPort.Counts.Unavailable -> {
                CompanyResult.StorageFailure("count applications")
            }
        }

    private fun confirmThenDelete(
        company: Company,
        contacts: List<ContactId>,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): CompanyResult<Unit> {
        val effect = ConfirmationEffect(CompanyId.ENTITY_TYPE, company.details.name, mapOf(CONTACTS to contacts.size))
        val action = ConfirmableAction(Company.DELETE_OPERATION, listOf(company.id.value.toString()), effect)
        return when (val outcome = confirmation.execute(ConfirmationRequest(requester, action, token))) {
            is ConfirmationResult.Confirmed -> {
                companies.delete(company.id, outcome).toResult().then { record(company, contacts, requester.actor) }
            }

            is ConfirmationResult.Unconfirmed -> {
                CompanyResult.Unconfirmed(outcome)
            }
        }
    }

    private fun record(
        company: Company,
        contacts: List<ContactId>,
        actor: Actor,
    ): CompanyResult<Unit> {
        val at = clock.storedNow()
        val name = listOf(FieldChange("name", company.details.name, null))
        val recorded =
            changelog.record(company.id.toEntityRef(), actor, at, "Deleted company", name) &&
                contacts.all { contact ->
                    changelog.record(contact.toEntityRef(), actor, at, "Deleted with its company")
                }
        return Unit
            .onlyIf(recorded, "changelog")
            .then {
                val published = contacts.all { contact -> events.publish(ContactDeleted(contact, actor, at)) }
                Unit.onlyIf(published, "publish event")
            }
    }

    private companion object {
        /** The effect's count of contacts deleted with the company (ADR-0041); the UI names it. */
        const val CONTACTS = "contacts"
    }
}
