// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.companies.application.port.spi.TaskLinksPort
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyLinks
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactLinks

/**
 * Reads what the applications and tasks contexts link to a company and its contacts, through the ports they implement
 * (named interface `spi`, ADR-0041, ADR-0049). The company delete calls it in its transaction before it deletes; it
 * is a use case of its own so that the delete takes one dependency for the other contexts. A company with
 * applications is [CompanyResult.HasApplications] (its delete is refused before a token is issued); otherwise the
 * result is the tasks linked to the company and, per contact in [contacts], what a contact delete would read: the
 * applications it is linked to, the interviews it takes part in and its tasks. The delete's cascade removes those
 * links without a trace, so the delete records them from this read. Any context unreadable is a storage failure, so
 * the delete deletes nothing.
 */
class FindCompanyLinksUseCase(
    private val applications: ApplicationCountsPort,
    private val tasks: TaskLinksPort,
    private val linkedApplications: LinkedApplicationsPort,
) {
    fun execute(
        company: CompanyId,
        contacts: List<ContactId>,
    ): CompanyResult<CompanyLinks> =
        when (val counts = applications.countByCompany(setOf(company.value))) {
            is ApplicationCountsPort.Counts.Counted -> {
                if (counts.of(company.value) == 0) linkedTasks(company, contacts) else CompanyResult.HasApplications
            }

            ApplicationCountsPort.Counts.Unavailable -> {
                CompanyResult.StorageFailure("count applications")
            }
        }

    private fun linkedTasks(
        company: CompanyId,
        contacts: List<ContactId>,
    ): CompanyResult<CompanyLinks> =
        when (val linked = tasks.linkedTo(setOf(company.value), contacts.map { it.value }.toSet())) {
            is TaskLinksPort.Links.Found -> withApplications(linked, contacts)
            TaskLinksPort.Links.Unavailable -> CompanyResult.StorageFailure("read linked tasks")
        }

    private fun withApplications(
        tasks: TaskLinksPort.Links.Found,
        contacts: List<ContactId>,
    ): CompanyResult<CompanyLinks> {
        val perContact = linkedMapOf<ContactId, ContactLinks>()
        for (contact in contacts) {
            val linked = linkedApplications.linkedTo(contact.value)
            if (linked !is LinkedApplicationsPort.Linked.Found) {
                return CompanyResult.StorageFailure("read linked applications")
            }
            val contactTasks = tasks.toContacts.filter { it.target == contact.value }.map { it.task }
            perContact[contact] = ContactLinks(linked.applications, linked.interviews, contactTasks)
        }
        return CompanyResult.Success(CompanyLinks(tasks.toCompanies.map { it.task }, perContact))
    }
}
