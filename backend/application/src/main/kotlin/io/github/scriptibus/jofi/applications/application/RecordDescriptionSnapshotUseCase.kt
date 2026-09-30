// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.RecordDescriptionSnapshotPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.DescriptionInput
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotRecording
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock
import java.util.UUID

/**
 * Records a source's current description (ADR-0046). In one transaction: the application and its source, the
 * input, the source's newest snapshot (read under the source's row lock, so two recordings of the same new text
 * cannot both store it), and, for a changed text, the new snapshot with its changelog entry. The same content
 * hash is `Unchanged`: nothing stored, no entry. A source's first snapshot is frozen at once if the application
 * is applied to already ([DescriptionSnapshot.firstOf]); a later one never is. The changelog entry names the
 * source, reason and content hash, never the text.
 */
class RecordDescriptionSnapshotUseCase(
    private val applications: ApplicationRepositoryPort,
    private val snapshots: DescriptionSnapshotRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : RecordDescriptionSnapshotPort {
    override fun execute(
        id: ApplicationId,
        source: SourceId,
        input: DescriptionInput,
        actor: Actor,
    ): ApplicationResult<SnapshotRecording> =
        transactions.inApplicationTransaction {
            applications
                .findById(id)
                .toResult()
                .then { it.withSource(source) }
                .then { application -> input.validate().toResult().then { record(application, source, it, input) } }
                .then { recording -> store(recording, actor) }
        }

    private fun record(
        application: Application,
        source: SourceId,
        text: DescriptionText,
        input: DescriptionInput,
    ): ApplicationResult<SnapshotRecording> =
        snapshots.latest(source).toResult().then { latest ->
            val id = SnapshotId(UUID.randomUUID())
            val now = clock.storedNow()
            val first = { DescriptionSnapshot(id, source, text, input.reason, now).firstOf(application) }
            ApplicationResult.Success(latest?.next(id, text, input.reason, now) ?: SnapshotRecording.Added(first()))
        }

    private fun store(
        recording: SnapshotRecording,
        actor: Actor,
    ): ApplicationResult<SnapshotRecording> {
        if (recording is SnapshotRecording.Unchanged) return ApplicationResult.Success(recording)
        val snapshot = recording.snapshot
        return snapshots.add(snapshot).toResult().then {
            recording.applicationIf(changelog.recordSnapshot(snapshot, actor), "changelog")
        }
    }
}
