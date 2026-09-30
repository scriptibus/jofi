// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.PostingImport
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

/** The entry of a new application: its details with values ([detailChanges]), personal ones only by name. */
internal fun ChangelogPort.recordCreated(
    application: Application,
    actor: Actor,
): Boolean =
    record(
        application.id.toEntityRef(),
        actor,
        application.createdAt,
        describe("Created application", null, application.details),
        detailChanges(null, application.details),
    )

/** The entry of a new source: its application and kind, never the link (it may carry tracking parameters). */
internal fun ChangelogPort.recordSource(
    source: ApplicationSource,
    actor: Actor,
    at: Instant,
): Boolean =
    record(
        source.id.toEntityRef(),
        actor,
        at,
        "Added source",
        listOf(
            FieldChange("application", null, source.application.value.toString()),
            FieldChange("kind", null, source.kind.name),
        ),
    )

/** The entry of a new description version: source, reason and content hash, never the text. */
internal fun ChangelogPort.recordSnapshot(
    snapshot: DescriptionSnapshot,
    actor: Actor,
): Boolean =
    record(
        snapshot.id.toEntityRef(),
        actor,
        snapshot.capturedAt,
        "Recorded job description",
        listOfNotNull(
            FieldChange("source", null, snapshot.source.value.toString()),
            FieldChange("reason", null, snapshot.reason.name),
            FieldChange("contentHash", null, snapshot.contentHash.hex),
            snapshot.frozenAt?.let { frozen -> FieldChange("frozenAt", null, frozen.toString()) },
        ),
    )

/**
 * The entry of an import's step from [before] (none when it starts) to [after]: status, failure, attempt and the
 * created application, plus the text's content hash at the start; never the text.
 */
internal fun ChangelogPort.recordImport(
    before: PostingImport?,
    after: PostingImport,
    actor: Actor,
): Boolean =
    record(
        after.id.toEntityRef(),
        actor,
        after.updatedAt,
        importAction(before, after),
        listOfNotNull(
            changeOf("status", before?.status, after.status),
            changeOf("failure", before?.failure, after.failure),
            changeOf("attempt", before?.attempt, after.attempt),
            changeOf("application", before?.application?.value, after.application?.value),
            after.text?.takeIf { before == null }?.let { FieldChange("contentHash", null, it.contentHash.hex) },
        ),
    )

private fun importAction(
    before: PostingImport?,
    after: PostingImport,
): String =
    when {
        before == null -> "Started posting import"
        after.application != null -> "Imported posting"
        after.failure != null -> "Posting import failed"
        else -> "Retried posting import"
    }

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
