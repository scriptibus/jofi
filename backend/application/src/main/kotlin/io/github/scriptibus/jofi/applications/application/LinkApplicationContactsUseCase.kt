// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.LinkApplicationContactsPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import java.time.Clock

/**
 * Replaces the contacts linked to an application. The version is checked first, then the limit of
 * `Application.MAX_CONTACTS`; an unchanged set stores nothing and writes no changelog entry. It stores through
 * `replaceContacts`, which writes only the links and the version, so a concurrent read/unread toggle is not
 * lost. A contact that does not exist (any more) is refused by the store's foreign key and rolls all of it back.
 *
 * The changelog entry names the field `contacts` with ids only (a contact's name is personal data of the
 * companies context): `before` the unlinked ids, `after` the newly linked ones, as the contact delete's
 * per-application entry records its one unlinked id.
 */
class LinkApplicationContactsUseCase(
    private val applications: ApplicationRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : LinkApplicationContactsPort {
    override fun execute(
        id: ApplicationId,
        contacts: Set<ContactRef>,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<Application> =
        transactions.inApplicationTransaction {
            applications
                .findById(id)
                .toResult()
                .then { it.basedOn(basedOnVersion) }
                .then { current -> withinLimit(contacts).then { link(current, it, actor) } }
        }

    private fun withinLimit(contacts: Set<ContactRef>): ApplicationResult<Set<ContactRef>> =
        if (contacts.size <= Application.MAX_CONTACTS) {
            ApplicationResult.Success(contacts)
        } else {
            val tooMany = ApplicationViolation(ApplicationField.CONTACTS, ApplicationProblem.TOO_MANY)
            ApplicationResult.Invalid(listOf(tooMany))
        }

    private fun link(
        current: Application,
        contacts: Set<ContactRef>,
        actor: Actor,
    ): ApplicationResult<Application> {
        val linked = current.linkContacts(contacts, clock.storedNow())
        if (linked == current) return ApplicationResult.Success(current)
        val unlinked = idsOf(current.contacts - contacts)
        val newlyLinked = idsOf(contacts - current.contacts)
        return applications.replaceContacts(linked).toResult().then {
            val recorded =
                changelog.record(
                    linked.id.toEntityRef(),
                    actor,
                    linked.updatedAt,
                    "Changed linked contacts",
                    listOf(FieldChange(CONTACTS, unlinked, newlyLinked)),
                )
            linked.applicationIf(recorded, "changelog")
        }
    }

    /** The ids, sorted so the entry does not depend on set order; null for none. */
    private fun idsOf(contacts: Set<ContactRef>): String? =
        contacts
            .map { it.value.toString() }
            .sorted()
            .joinToString(",")
            .ifEmpty { null }

    private companion object {
        /** The field of the contact links in the changelog, as the contact delete writes it. */
        const val CONTACTS = "contacts"
    }
}
