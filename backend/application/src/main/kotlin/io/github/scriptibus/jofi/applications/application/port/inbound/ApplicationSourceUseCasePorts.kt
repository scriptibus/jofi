// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.inbound

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.DescriptionDiff
import io.github.scriptibus.jofi.applications.domain.DescriptionInput
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotRecording
import io.github.scriptibus.jofi.applications.domain.SnapshotSummary
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceInput
import io.github.scriptibus.jofi.shared.domain.Actor

// Inbound ports for sources and description snapshots (#78, ADR-0046), implemented by the use cases of the same name
// (#86, #96). Freezing has no port of its own: the status change freezes in its own transaction
// (`ChangeApplicationStatusPort`, `DescriptionSnapshotRepositoryPort.freeze`). Posting text is untrusted data: it is
// stored and shown (sanitised), never followed as instructions, and never goes into a changelog entry. An unknown
// application is `NotFound`, a source or snapshot that is not the application's `SourceNotFound` or `SnapshotNotFound`.
// Mutations take the acting `Actor` and record it; none of them is a new version of the application, so none takes
// `basedOnVersion`.

/**
 * Adds a source to the application (#96): the same job found in another place, or where an import found
 * it. With [SourceInput.description] the source's first snapshot (`SnapshotReason.DISCOVERY`) is stored with
 * it, frozen at once if the application is applied to already (`DescriptionSnapshot.discovery`). At most
 * `Application.MAX_SOURCES`: `Invalid` (SOURCES, TOO_MANY), from `Application.addSource` or the
 * store's `SourceLimitReached`. Writes one changelog entry for the source and one for its snapshot.
 */
interface AddApplicationSourcePort {
    fun execute(
        id: ApplicationId,
        input: SourceInput,
        actor: Actor,
    ): ApplicationResult<ApplicationSource>
}

/**
 * Records [input] as the source's current description (#86): a new version, unless its content hash equals
 * the newest snapshot's (`SnapshotRecording.Unchanged`, nothing stored, no changelog entry). A frozen
 * snapshot never changes; a changed text after applying is a new, unfrozen version (`DescriptionSnapshot.next`).
 * A source's first text, recorded after applying, is frozen at once (`DescriptionSnapshot.firstOf`).
 */
interface RecordDescriptionSnapshotPort {
    fun execute(
        id: ApplicationId,
        source: SourceId,
        input: DescriptionInput,
        actor: Actor,
    ): ApplicationResult<SnapshotRecording>
}

/** The versions of one source's description (#86), oldest first, without their texts. */
interface ListDescriptionSnapshotsPort {
    fun execute(
        id: ApplicationId,
        source: SourceId,
    ): ApplicationResult<List<SnapshotSummary>>
}

/** One version of a description with its full text (#86). */
interface GetDescriptionSnapshotPort {
    fun execute(
        id: ApplicationId,
        snapshot: SnapshotId,
    ): ApplicationResult<DescriptionSnapshot>
}

/**
 * The difference between two versions of the application's descriptions (#86), from [from] to [to]; they may
 * belong to different sources (the same job posted twice). Reads only.
 */
interface DiffDescriptionSnapshotsPort {
    fun execute(
        id: ApplicationId,
        from: SnapshotId,
        to: SnapshotId,
    ): ApplicationResult<DescriptionDiff>
}
