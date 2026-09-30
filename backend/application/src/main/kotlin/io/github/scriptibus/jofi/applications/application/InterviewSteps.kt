// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.shared.domain.FieldChange

// How the interview use cases (ADR-0048) map store results and record their changes.

/**
 * A store result where the contacts in question are participants: a missing one (`interview_participant_contact_fk`)
 * is `Invalid` (PARTICIPANTS, NOT_FOUND), not the application's contact links.
 */
internal fun <T> ApplicationStoreResult<T>.withParticipants(): ApplicationResult<T> =
    if (this == ApplicationStoreResult.ContactNotFound) {
        val missing = ApplicationViolation(ApplicationField.PARTICIPANTS, ApplicationProblem.NOT_FOUND)
        ApplicationResult.Invalid(listOf(missing))
    } else {
        toResult()
    }

/** A store result for an interview of an application that exists: not found means it has no such interview. */
internal fun <T> ApplicationStoreResult<T>.interviewResult(): ApplicationResult<T> =
    if (this == ApplicationStoreResult.NotFound) ApplicationResult.InterviewNotFound else withParticipants()

/** The interview if the caller based its change on its current version, else [ApplicationResult.VersionConflict]. */
internal fun Interview.basedOn(version: Long): ApplicationResult<Interview> =
    if (this.version == version) ApplicationResult.Success(this) else ApplicationResult.VersionConflict

/**
 * What changed between two versions of an interview's details, with values: type, start, zone and outcome. Notes
 * and participants are personal data (the user's words about people, who took part), so only [describeInterview]
 * names them.
 */
internal fun interviewChanges(
    before: InterviewDetails?,
    after: InterviewDetails?,
): List<FieldChange> =
    listOfNotNull(
        changeOf("type", before?.type, after?.type),
        changeOf("startsAt", before?.time?.startsAt, after?.time?.startsAt),
        changeOf("timeZone", before?.time?.zone?.id, after?.time?.zone?.id),
        changeOf("outcome", before?.outcome, after?.outcome),
    )

/** [action], plus the names of the fields [interviewChanges] keeps no values of, if they changed. */
internal fun describeInterview(
    action: String,
    before: InterviewDetails?,
    after: InterviewDetails,
): String {
    val named =
        listOf<Pair<String, (InterviewDetails) -> Any?>>(
            "participants" to { it.participants.ifEmpty { null } },
            "preparation notes" to { it.preparationNotes },
            "notes" to { it.notes },
        ).filter { (_, value) -> before?.let(value) != value(after) }
            .map { (name, _) -> name }
    return if (named.isEmpty()) action else "$action; also changed: ${named.joinToString()}"
}

/** The application an interview belongs to, which its log and delete entries name (a deleted one keeps that link). */
internal fun applicationChange(
    before: ApplicationId?,
    after: ApplicationId?,
): FieldChange = FieldChange(APPLICATION_FIELD, before?.value?.toString(), after?.value?.toString())

private const val APPLICATION_FIELD = "application"
