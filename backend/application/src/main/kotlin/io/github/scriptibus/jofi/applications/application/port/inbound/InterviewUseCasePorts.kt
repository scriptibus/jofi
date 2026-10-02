// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.inbound

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewInput
import io.github.scriptibus.jofi.applications.domain.InterviewSummary
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.Paged

// Inbound ports for interviews and calls (#79, ADR-0048), implemented by the use cases of the same name (#91, #92).
// An interview is an aggregate of its own with its own `version`; none of these writes the application, so none
// conflicts with an edit of it. An unknown application is `NotFound`, an interview that is not the application's
// `InterviewNotFound`; a participant that is no contact is `Invalid` (PARTICIPANTS, NOT_FOUND, from the store's
// `ContactNotFound`). Mutations take the acting `Actor` and write a changelog entry (entity `interview`) that names
// the changed fields, never the notes or who took part, since both are personal data. `basedOnVersion` is the
// `Interview.version` the caller last read: a stale one is `VersionConflict`, checked first, even for a no-op.
// Timestamps are `clock.instant().truncatedTo(ChronoUnit.MICROS)`.

/**
 * Logs an interview or call of the application (#91), before or after it took place, and publishes
 * `InterviewScheduled` once it is stored (`Interview.log`).
 */
interface LogInterviewPort {
    fun execute(
        application: ApplicationId,
        input: InterviewInput,
        actor: Actor,
    ): ApplicationResult<Interview>
}

/**
 * Replaces **all** details of the interview with [input] (a PUT; a field left out is cleared), through
 * `InterviewRepositoryPort.update` (#91). Unchanged details store nothing and write no changelog entry; a changed
 * start publishes `InterviewRescheduled` (`Interview.edit`).
 */
interface UpdateInterviewPort {
    fun execute(
        application: ApplicationId,
        id: InterviewId,
        input: InterviewInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<Interview>
}

interface GetInterviewPort {
    fun execute(
        application: ApplicationId,
        id: InterviewId,
    ): ApplicationResult<Interview>
}

/**
 * One page of the interviews and calls of the application in the order they start (#91, ADR-0056), oldest first for
 * [SortDirection.ASCENDING] and newest first for [SortDirection.DESCENDING], so every interview is reachable. Entries
 * are [InterviewSummary]s with an excerpt of both notes; `GetInterviewPort` has the whole interview. A [page] out of
 * range is `Invalid` (PAGE, SIZE).
 */
interface ListInterviewsPort {
    fun execute(
        application: ApplicationId,
        page: PageInput,
        direction: SortDirection,
    ): ApplicationResult<Paged<InterviewSummary>>
}

/**
 * Deletes an interview in two steps (ADR-0039, #91): without [token] it answers [ApplicationResult.Unconfirmed]
 * with a token bound to [Interview.DELETE_OPERATION], the interview id and the effect
 * `ConfirmationEffect("interview", <type and local start>)`; with the token, it deletes. The actor is [requester]'s.
 */
interface DeleteInterviewPort {
    fun execute(
        application: ApplicationId,
        id: InterviewId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit>
}

/**
 * The interviews and calls still to come across all applications (#92): starting now or later and not cancelled,
 * soonest first, at most [Interview.MAX_UPCOMING], each with its application's title. Reads only.
 */
interface ListUpcomingInterviewsPort {
    fun execute(): ApplicationResult<List<UpcomingInterview>>
}
