// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.FieldChange
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

// How the company use cases record their changes in the changelog (spec §13).

/** "Now" at the precision of `timestamptz` (ADR-0041). */
internal fun Clock.storedNow(): Instant = instant().truncatedTo(ChronoUnit.MICROS)

/** Appends one changelog entry; false if the store refused it (the caller then rolls back). */
internal fun ChangelogPort.record(
    entity: EntityRef,
    actor: Actor,
    at: Instant,
    description: String,
    fields: List<FieldChange> = emptyList(),
): Boolean = append(ChangelogEntry(entity, actor, at, ChangeSummary(description, fields))) is ChangelogResult.Success

/**
 * The entry of a [task] whose link to the deleted [target] the delete clears (ADR-0049): ids only, the link as the
 * tasks context records it (`company:<id>`, `contact:<id>`). False if the store refused it.
 */
internal fun ChangelogPort.recordClearedLink(
    task: EntityRef,
    target: EntityRef,
    actor: Actor,
    at: Instant,
): Boolean {
    val cleared = listOf(FieldChange("link", "${target.type}:${target.id}", null))
    return record(task, actor, at, "Cleared the link to a deleted ${target.type}", cleared)
}

/**
 * What changed between two versions of the details. Research notes are free text that may hold
 * personal data, so only the description says they changed ([describe]), never their text.
 */
internal fun detailChanges(
    before: CompanyDetails?,
    after: CompanyDetails,
): List<FieldChange> =
    listOfNotNull(
        changeOf("name", before?.name, after.name),
        changeOf("website", before?.website, after.website),
        changeOf("industry", before?.industry, after.industry),
        changeOf("size", before?.size, after.size),
        changeOf("locations", before?.locations?.ifEmpty { null }, after.locations.ifEmpty { null }),
        changeOf("careersPage", before?.careersPage, after.careersPage),
    )

/** [action], plus a note if the research notes changed between [before] and [after]. */
internal fun describe(
    action: String,
    before: CompanyDetails?,
    after: CompanyDetails,
): String = if (before?.researchNotes != after.researchNotes) "$action; research notes changed" else action

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
