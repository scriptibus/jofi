// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.DescriptionDiff
import io.github.scriptibus.jofi.applications.domain.DescriptionInput
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SnapshotRecording
import io.github.scriptibus.jofi.applications.domain.SnapshotSummary
import io.github.scriptibus.jofi.applications.domain.SourceInput
import java.time.Instant
import java.util.UUID

// Posting texts are untrusted data and links may carry personal tracking parameters: DTOs that hold either
// print neither. Clients render descriptions sanitised.

/**
 * Body of `POST /api/applications/{id}/sources`: where the job was also found. [originalUrl] is required for
 * `URL`; [discoveredAt] defaults to now; [description], the posting's text there, becomes the source's first snapshot.
 */
data class AddApplicationSourceRequest(
    val kind: PostingSourceKind,
    val originalUrl: String? = null,
    val discoveredAt: Instant? = null,
    val description: String? = null,
) {
    fun toInput(): SourceInput = SourceInput(kind.mapByName(), originalUrl, discoveredAt, description)

    override fun toString(): String = "AddApplicationSourceRequest(kind=$kind, discoveredAt=$discoveredAt)"
}

/** One place the job was found; [offlineSince] is set while the posting is gone ([online] false). */
data class ApplicationSourceResponse(
    val id: UUID,
    val kind: PostingSourceKind,
    val originalUrl: String?,
    val discoveredAt: Instant,
    val online: Boolean,
    val offlineSince: Instant?,
) {
    override fun toString(): String = "ApplicationSourceResponse(id=$id, kind=$kind, online=$online)"

    companion object {
        fun from(source: ApplicationSource): ApplicationSourceResponse =
            ApplicationSourceResponse(
                source.id.value,
                source.kind.mapByName(),
                source.originalUrl?.value,
                source.discoveredAt,
                source.online,
                source.offlineSince,
            )
    }
}

/** Body of `POST /api/applications/{id}/sources/{sourceId}/snapshots`: the posting's current text. */
data class RecordDescriptionSnapshotRequest(
    val description: String,
) {
    /** Recorded by hand (the user or a chat), so the reason is always `MANUAL`. */
    fun toInput(): DescriptionInput = DescriptionInput(description, SnapshotReason.MANUAL)

    override fun toString(): String = "RecordDescriptionSnapshotRequest(length=${description.length})"
}

/** One version of a description without its text; [length] counts its characters. */
data class DescriptionSnapshotSummaryResponse(
    val id: UUID,
    val sourceId: UUID,
    val contentHash: String,
    val reason: SnapshotCaptureReason,
    val capturedAt: Instant,
    val frozenAt: Instant?,
    val length: Int,
) {
    companion object {
        fun from(summary: SnapshotSummary): DescriptionSnapshotSummaryResponse =
            DescriptionSnapshotSummaryResponse(
                summary.id.value,
                summary.source.value,
                summary.contentHash.hex,
                summary.reason.mapByName(),
                summary.capturedAt,
                summary.frozenAt,
                summary.length,
            )
    }
}

/** Answer of recording a description: the newest version, and whether this request [added] it. */
data class DescriptionSnapshotRecordedResponse(
    val snapshot: DescriptionSnapshotSummaryResponse,
    val added: Boolean,
) {
    companion object {
        fun from(recording: SnapshotRecording): DescriptionSnapshotRecordedResponse =
            DescriptionSnapshotRecordedResponse(
                DescriptionSnapshotSummaryResponse.from(recording.snapshot.summary()),
                recording is SnapshotRecording.Added,
            )
    }
}

/** JSON body of `GET /api/applications/{id}/sources/{sourceId}/snapshots`: the versions, oldest first. */
data class DescriptionSnapshotListResponse(
    val snapshots: List<DescriptionSnapshotSummaryResponse>,
)

/** One version of a description with its text (Markdown or plain text as found; render it sanitised). */
data class DescriptionSnapshotResponse(
    val id: UUID,
    val sourceId: UUID,
    val description: String,
    val contentHash: String,
    val reason: SnapshotCaptureReason,
    val capturedAt: Instant,
    val frozenAt: Instant?,
) {
    override fun toString(): String = "DescriptionSnapshotResponse(id=$id, reason=$reason, frozenAt=$frozenAt)"

    companion object {
        fun from(snapshot: DescriptionSnapshot): DescriptionSnapshotResponse =
            DescriptionSnapshotResponse(
                snapshot.id.value,
                snapshot.source.value,
                snapshot.text.value,
                snapshot.contentHash.hex,
                snapshot.reason.mapByName(),
                snapshot.capturedAt,
                snapshot.frozenAt,
            )
    }
}

/** A run of text of a diff, in both versions, only the newer (`ADDED`) or only the older (`REMOVED`). */
data class DiffSegmentDto(
    val operation: DiffSegmentOperation,
    val text: String,
) {
    override fun toString(): String = "DiffSegmentDto(operation=$operation, length=${text.length})"
}

/** JSON body of `GET /api/applications/{id}/description-diff`: the segments turning [from]'s text into [to]'s. */
data class DescriptionDiffResponse(
    val from: UUID,
    val to: UUID,
    val segments: List<DiffSegmentDto>,
) {
    companion object {
        fun from(diff: DescriptionDiff): DescriptionDiffResponse =
            DescriptionDiffResponse(
                diff.from.value,
                diff.to.value,
                diff.segments.map { DiffSegmentDto(it.operation.mapByName(), it.text) },
            )
    }
}
