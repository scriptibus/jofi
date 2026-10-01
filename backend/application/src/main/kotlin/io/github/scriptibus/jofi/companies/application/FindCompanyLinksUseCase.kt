// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.spi.ApplicationCountsPort
import io.github.scriptibus.jofi.companies.application.port.spi.TaskLinksPort
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.ContactId

/**
 * Reads what the applications and tasks contexts link to a company and its contacts, through the ports they implement
 * (named interface `spi`, ADR-0041, ADR-0049). The company delete calls it in its transaction before it deletes; it
 * is a use case of its own so that the delete takes one dependency for both contexts. A company with applications is
 * [CompanyResult.HasApplications] (its delete is refused before a token is issued); otherwise the result is the tasks
 * linked to the company or to one of [contacts], whose links the delete clears. Either context unreadable is a
 * storage failure, so the delete deletes nothing.
 */
class FindCompanyLinksUseCase(
    private val applications: ApplicationCountsPort,
    private val tasks: TaskLinksPort,
) {
    fun execute(
        company: CompanyId,
        contacts: List<ContactId>,
    ): CompanyResult<TaskLinksPort.Links.Found> =
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
    ): CompanyResult<TaskLinksPort.Links.Found> =
        when (val linked = tasks.linkedTo(setOf(company.value), contacts.map { it.value }.toSet())) {
            is TaskLinksPort.Links.Found -> CompanyResult.Success(linked)
            TaskLinksPort.Links.Unavailable -> CompanyResult.StorageFailure("read linked tasks")
        }
}
