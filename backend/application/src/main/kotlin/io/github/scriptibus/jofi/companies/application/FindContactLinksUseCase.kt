// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.companies.application.port.spi.TaskLinksPort
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactLinks
import io.github.scriptibus.jofi.companies.domain.ContactResult

/**
 * Reads what the applications and tasks contexts link to a contact, through the ports they implement (named interface
 * `spi`, ADR-0041, ADR-0049). The contact delete calls it in its transaction before it deletes; it is a use case of
 * its own so that the delete takes one dependency for both contexts. Either context unreadable is a storage failure,
 * so the delete deletes nothing.
 */
class FindContactLinksUseCase(
    private val applications: LinkedApplicationsPort,
    private val tasks: TaskLinksPort,
) {
    fun execute(contact: ContactId): ContactResult<ContactLinks> =
        when (val linked = applications.linkedTo(contact.value)) {
            is LinkedApplicationsPort.Linked.Found -> withTasks(contact, linked)
            LinkedApplicationsPort.Linked.Unavailable -> ContactResult.StorageFailure("read linked applications")
        }

    private fun withTasks(
        contact: ContactId,
        linked: LinkedApplicationsPort.Linked.Found,
    ): ContactResult<ContactLinks> =
        when (val linkedTasks = tasks.linkedTo(emptySet(), setOf(contact.value))) {
            is TaskLinksPort.Links.Found -> {
                ContactResult.Success(
                    ContactLinks(linked.applications, linked.interviews, linkedTasks.toContacts.map { it.task }),
                )
            }

            TaskLinksPort.Links.Unavailable -> {
                ContactResult.StorageFailure("read linked tasks")
            }
        }
}
