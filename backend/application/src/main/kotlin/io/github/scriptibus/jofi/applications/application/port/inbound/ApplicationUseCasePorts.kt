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
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.applications.domain.StatusChangeInput
import io.github.scriptibus.jofi.applications.domain.TimelinePage
import io.github.scriptibus.jofi.applications.domain.TimelineQuery
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken

// Inbound ports (ADR-0041): what the REST controllers and MCP tools may ask of the applications
// context. Each is implemented by the use case of the same name (#82, #83, #84, #90). Mutations take the
// acting `Actor` and record it in the changelog (spec §13); notes and reasons are free text, so the
// entry names the changed fields, not their text. `basedOnVersion` is the `Application.version` the
// caller last read: a stale one is `VersionConflict`, checked first, even for a no-op. A company or
// contact that does not exist is `Invalid` (COMPANY or CONTACTS, NOT_FOUND). Timestamps are
// `clock.instant().truncatedTo(ChronoUnit.MICROS)`, the precision of `timestamptz`.

/**
 * Creates an application by hand (#82) in `ApplicationStatus.INITIAL`, storing its first history entry
 * (`StatusChange.initial`) with it; scanners and imports create theirs as unread (#96).
 */
interface CreateApplicationPort {
    fun execute(
        input: ApplicationInput,
        actor: Actor,
    ): ApplicationResult<Application>
}

/**
 * Replaces **all** details with [input] (a PUT, not a patch): a field left out is cleared. Contacts, the
 * status and its decline reason, the unread flag and the scores stay: the use case stores through
 * `ApplicationRepositoryPort.updateDetails`, which never writes them, so a concurrent read/unread toggle is
 * not lost. Unchanged details store nothing and write no changelog entry.
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
 * (`Invalid`, CONTACTS, TOO_MANY). An unchanged set is a no-op. Stores through
 * `ApplicationRepositoryPort.replaceContacts`, which never writes the details, the flag or the scores.
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
 * id and the effect `ConfirmationEffect("application", <title>, counts)`, counting what the delete cascades
 * to: `contactLinks`, `statusChanges`, `sources` and `snapshots` (later contracts add theirs, e.g. interviews
 * and tasks). With the token, it deletes, writes the changelog entry and publishes `ApplicationDeleted`. The
 * actor is [requester]'s.
 */
interface DeleteApplicationPort {
    fun execute(
        id: ApplicationId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit>
}

/**
 * Moves the application to another status (#84) along the matrix of ADR-0044 (`Application.changeStatus`).
 * `DECLINED` and `REJECTED` need a decline category (`Invalid`, DECLINE_CATEGORY, REQUIRED), which with
 * the reason becomes the application's decline reason; any other status clears it, and a category there is
 * `Invalid` (NOT_APPLICABLE). A move the matrix does not allow is `InvalidTransition`; moving to the current
 * status with the same reason is a no-op. Stores through `ApplicationRepositoryPort.changeStatus` (the
 * history entry with it), writes a changelog entry (field `status`, before and after, plus `declineReason`, the
 * category before and after, when that changes; a self-move correcting the reason records only `declineReason`;
 * the reason's text stays out of it) and publishes `ApplicationStatusChanged` (whose `from` equals `to` for such a
 * correction). If the move `freezesDescriptions` (the application is
 * first applied to, ADR-0046), it freezes the job descriptions in the same transaction through
 * `DescriptionSnapshotRepositoryPort.freeze` (as of the change's time), writing one changelog entry per frozen snapshot
 * (entity `description_snapshot`, same actor), so a status change and its freeze are stored together or not at all.
 */
interface ChangeApplicationStatusPort {
    fun execute(
        id: ApplicationId,
        input: StatusChangeInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<Application>
}

/** The application's status history (#84), oldest entry first. */
interface GetApplicationStatusHistoryPort {
    fun execute(id: ApplicationId): ApplicationResult<List<StatusChange>>
}

/**
 * One page of the application's timeline (#87), newest first: its changes (field names only), status changes,
 * captured job descriptions, interviews (at their start) and linked tasks, merged from one read per source.
 * `NotFound` if there is no such application, `StorageFailure` if a source cannot be read.
 */
interface GetApplicationTimelinePort {
    fun execute(
        id: ApplicationId,
        query: TimelineQuery,
    ): ApplicationResult<TimelinePage>
}
