// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import java.time.Instant

/**
 * Stores interviews and calls (tables `interview` and `interview_participant`; implemented with the use cases in
 * #91 and #92). An interview is not a version of its application: nothing here writes the `application` row. The
 * use case appends the changelog entry (entity [InterviewId.ENTITY_TYPE]; field names only, since notes describe
 * people) in the same transaction. Implementations never throw and never log row data. Foreign-key violations are
 * mapped **by constraint name** (ADR-0041): `interview_application_fk` to [ApplicationStoreResult.NotFound],
 * `interview_participant_contact_fk` to [ApplicationStoreResult.ContactNotFound]; anything else is a
 * `StorageFailure`.
 */
interface InterviewRepositoryPort {
    /** Stores a new interview with its participants, both or neither. */
    fun add(interview: Interview): ApplicationStoreResult<Unit>

    /**
     * Stores [interview]'s details only if the stored version is exactly one below [interview]'s (see
     * [Interview.edit]), [ApplicationStoreResult.VersionConflict] otherwise; rewrites `interview_participant` only
     * when the stored set differs, in the same transaction.
     */
    fun update(interview: Interview): ApplicationStoreResult<Unit>

    /** The interview [id] of [application]; [ApplicationStoreResult.NotFound] if the application has no such one. */
    fun findById(
        application: ApplicationId,
        id: InterviewId,
    ): ApplicationStoreResult<Interview>

    /**
     * The interviews of [application] in the order they start (then by id); empty for none. Whether the application
     * exists is the use case's check.
     */
    fun listByApplication(application: ApplicationId): ApplicationStoreResult<List<Interview>>

    /**
     * The interviews of every application that start at or after [from] and are not `CANCELLED`, soonest first
     * (then by id), at most [limit] (`interview_starts_at_idx`), each with its application's title.
     */
    fun upcoming(
        from: Instant,
        limit: Int,
    ): ApplicationStoreResult<List<UpcomingInterview>>

    /**
     * Deletes the interview and, by `ON DELETE CASCADE`, its participant rows (the contacts stay). [proof] is what
     * the confirmation gate returned (ADR-0039): the adapter answers [ApplicationStoreResult.NotConfirmed] unless
     * `proof.covers(Interview.DELETE_OPERATION, id.value.toString())`, and [ApplicationStoreResult.NotFound] if
     * [application] has no such interview.
     */
    fun delete(
        application: ApplicationId,
        id: InterviewId,
        proof: ConfirmationResult.Confirmed,
    ): ApplicationStoreResult<Unit>
}
