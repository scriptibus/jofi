// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.inbound

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken

// Inbound ports (ADR-0041): what the REST controllers and MCP tools may ask of the applications
// context. Each is implemented by the use case of the same name (#82, #83, #90). Mutations take the
// acting `Actor` and record it in the changelog (spec §13); notes and reasons are free text, so the
// entry names the changed fields, not their text. `basedOnVersion` is the `Application.version` the
// caller last read: a stale one is `VersionConflict`, checked first, even for a no-op. A company or
// contact that does not exist is `Invalid` (COMPANY or CONTACTS, NOT_FOUND). Timestamps are
// `clock.instant().truncatedTo(ChronoUnit.MICROS)`, the precision of `timestamptz`.

/** Creates an application by hand (#82); scanners and imports create theirs as unread (#96). */
interface CreateApplicationPort {
    fun execute(
        input: ApplicationInput,
        actor: Actor,
    ): ApplicationResult<Application>
}

/**
 * Replaces **all** details with [input] (a PUT, not a patch): a field left out is cleared. Contacts,
 * the unread flag and the scores stay. Unchanged details store nothing and write no changelog entry.
 */
interface UpdateApplicationPort {
    fun execute(
        id: ApplicationId,
        input: ApplicationInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<Application>
}

interface GetApplicationPort {
    fun execute(id: ApplicationId): ApplicationResult<Application>
}

/** The list with filters, sort and paging (#83). */
interface SearchApplicationsPort {
    fun execute(search: ApplicationSearch): ApplicationResult<ApplicationPage<Application>>
}

/**
 * Marks the application read or unread (#82): no version check and no new version, since opening an
 * application must not conflict with an edit; a changed flag writes a changelog entry.
 */
interface SetApplicationUnreadPort {
    fun execute(
        id: ApplicationId,
        unread: Boolean,
        actor: Actor,
    ): ApplicationResult<Application>
}

/**
 * Links exactly [contacts] to the application (#90), replacing the linked set: linking and unlinking are
 * both "read, change the set, send it back with the version". At most `Application.MAX_CONTACTS`
 * (`Invalid`, CONTACTS, TOO_MANY). An unchanged set is a no-op.
 */
interface LinkApplicationContactsPort {
    fun execute(
        id: ApplicationId,
        contacts: Set<ContactRef>,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<Application>
}

/**
 * Deletes an application in two steps (ADR-0039): without [token] it answers
 * [ApplicationResult.Unconfirmed] with a token bound to [Application.DELETE_OPERATION], the application
 * id and the effect `ConfirmationEffect("application", <title>)` (later contracts add counts, e.g. of
 * interviews and tasks). With the token, it deletes. The actor is [requester]'s.
 */
interface DeleteApplicationPort {
    fun execute(
        id: ApplicationId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit>
}
