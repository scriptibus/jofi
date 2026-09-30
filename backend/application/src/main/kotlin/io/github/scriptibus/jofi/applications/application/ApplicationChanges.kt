// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import java.time.Instant

// How the application use cases record their changes in the changelog (spec §13).

/** Appends one changelog entry; false if the store refused it (the caller then rolls back). */
internal fun ChangelogPort.record(
    entity: EntityRef,
    actor: Actor,
    at: Instant,
    description: String,
    fields: List<FieldChange> = emptyList(),
): Boolean = append(ChangelogEntry(entity, actor, at, ChangeSummary(description, fields))) is ChangelogResult.Success

/**
 * What changed between two versions of the details, with values. Portal notes, the pay band and the
 * offer are left out: notes are the user's free text, pay and offer are personal (#52, ADR-0041), so
 * only [describe] names them.
 */
internal fun detailChanges(
    before: ApplicationDetails?,
    after: ApplicationDetails,
): List<FieldChange> {
    val was = before?.languageAndTone
    val now = after.languageAndTone
    return listOfNotNull(
        changeOf("title", before?.title, after.title),
        changeOf("company", before?.company?.value, after.company.value),
        changeOf("location", before?.location, after.location),
        changeOf("remoteShare", before?.remoteShare?.percent, after.remoteShare?.percent),
        changeOf("employmentType", before?.employmentType, after.employmentType),
        changeOf("seniority", before?.seniority, after.seniority),
        changeOf("deadline", before?.deadline, after.deadline),
        changeOf("howApplied", before?.howApplied, after.howApplied),
        changeOf("postingLanguage", was?.postingLanguage, now.postingLanguage),
        changeOf("applicationLanguage", was?.applicationLanguage, now.applicationLanguage),
        changeOf("formOfAddress", was?.formOfAddress, now.formOfAddress),
        changeOf("tone", was?.tone, now.tone),
    )
}

/** [action], plus the names of the fields [detailChanges] keeps no values of, if they changed. */
internal fun describe(
    action: String,
    before: ApplicationDetails?,
    after: ApplicationDetails,
): String {
    val named =
        listOf<Pair<String, (ApplicationDetails) -> Any?>>(
            "portal notes" to { it.portalNotes },
            "pay band" to { it.payBand },
            "offer" to { it.offer },
        ).filter { (_, value) -> before?.let(value) != value(after) }
            .map { (name, _) -> name }
    return if (named.isEmpty()) action else "$action; also changed: ${named.joinToString()}"
}

/** A field change, or nothing when the value stayed the same. */
internal fun changeOf(
    field: String,
    before: Any?,
    after: Any?,
): FieldChange? {
    val old = before?.toString()
    val new = after?.toString()
    return if (old == new) null else FieldChange(field, old, new)
}
