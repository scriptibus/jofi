// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.text.normalizedText
import io.github.scriptibus.jofi.shared.domain.text.textProblem
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Identifies one snapshot of a job description. */
@JvmInline
value class SnapshotId(
    val value: UUID,
) {
    /** How changelog entries refer to this snapshot (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of description snapshots; never rename it, stored entries use it. */
        const val ENTITY_TYPE = "description_snapshot"
    }
}

/** Why a snapshot was taken (spec §6.1). Never rename a constant: the database stores the names. */
enum class SnapshotReason {
    /** The text when the source was found. */
    DISCOVERY,

    /** A scanner or check found the posting changed (M4). */
    CHANGE_DETECTED,

    /** The user (or the chat) recorded the text by hand. */
    MANUAL,
}

/**
 * The text of a job posting, plain text or Markdown as found: untrusted data, never instructions, and
 * rendered sanitised. [normalize] brings raw text into the stored form (NFC, line breaks as `\n`,
 * trimmed), so the same posting fetched twice hashes the same (ADR-0046). [toString] prints only the length.
 */
@JvmInline
value class DescriptionText(
    val value: String,
) {
    init {
        require(textProblem(value, MAX_LENGTH) == null && '\r' !in value) { "A description text breaks an invariant" }
    }

    val contentHash: ContentHash get() = ContentHash.of(value)

    override fun toString(): String = "DescriptionText(${value.length} chars)"

    companion object {
        /** Postings are rarely longer than 20,000 characters; this leaves room without storing whole pages. */
        const val MAX_LENGTH = 100_000

        fun normalize(raw: String): String =
            raw
                .normalizedText()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
    }
}

/**
 * A job description to record for a source as a new version (#86), as entered or fetched: [validate] brings
 * it into the stored form and requires some text. [toString] leaves out the text.
 */
data class DescriptionInput(
    val text: String,
    val reason: SnapshotReason,
) {
    fun validate(): ApplicationValidation<DescriptionText> {
        val checks = InputChecks()
        val text = checks.description(text, required = true)
        if (text == null || checks.count > 0) return ApplicationValidation.Invalid(checks.violations)
        return ApplicationValidation.Valid(text)
    }

    override fun toString(): String = "DescriptionInput(reason=$reason, length=${text.length})"
}

/**
 * SHA-256 of a [DescriptionText]'s UTF-8 bytes as 64 lower-case hex digits (ADR-0046): the same text is
 * the same hash, so recording an unchanged posting stores nothing. The database checks it matches the text.
 */
@JvmInline
value class ContentHash(
    val hex: String,
) {
    init {
        require(SHAPE.matches(hex)) { "A content hash is 64 lower-case hex digits" }
    }

    override fun toString(): String = hex

    companion object {
        private val SHAPE = Regex("^[0-9a-f]{64}$")

        fun of(text: String): ContentHash =
            ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray())))
    }
}

/**
 * One version of a source's job description (spec §6.1): the full [text] as captured [at][capturedAt],
 * stored locally so it survives the posting going offline. Snapshots never change: a new text is a new
 * snapshot ([next]). [frozenAt] is set once, when the application was first applied to (ADR-0046); a frozen
 * snapshot is what the user applied for and stays so ([freeze] keeps the first time, [toFreeze] picks which
 * snapshot of a source to freeze). [toString] leaves out the text.
 */
data class DescriptionSnapshot(
    val id: SnapshotId,
    val source: SourceId,
    val text: DescriptionText,
    val reason: SnapshotReason,
    val capturedAt: Instant,
    val frozenAt: Instant? = null,
) {
    init {
        require(frozenAt == null || !frozenAt.isBefore(capturedAt)) { "A snapshot cannot be frozen before it exists" }
    }

    val contentHash: ContentHash get() = text.contentHash
    val frozen: Boolean get() = frozenAt != null

    /** This snapshot frozen [at]; a frozen one stays exactly as it is. */
    fun freeze(at: Instant): DescriptionSnapshot = if (frozen) this else copy(frozenAt = at)

    /**
     * What recording [text] after this snapshot means: [SnapshotRecording.Unchanged] for the same content
     * hash, otherwise a new, unfrozen snapshot [id] of the same source. This snapshot never changes, frozen or not.
     */
    fun next(
        id: SnapshotId,
        text: DescriptionText,
        reason: SnapshotReason,
        at: Instant,
    ): SnapshotRecording =
        if (text.contentHash == contentHash) {
            SnapshotRecording.Unchanged(this)
        } else {
            SnapshotRecording.Added(DescriptionSnapshot(id, source, text, reason, at))
        }

    fun summary(): SnapshotSummary =
        SnapshotSummary(id, source, contentHash, reason, capturedAt, frozenAt, text.value.length)

    override fun toString(): String =
        "DescriptionSnapshot(id=${id.value}, source=${source.value}, reason=$reason, frozen=$frozen)"

    companion object {
        /**
         * The source's first snapshot, captured [at] for [application]: frozen at once if the application is
         * applied to already ([ApplicationStatus.impliesApplied]; a source found after applying shows what was
         * applied for, ADR-0046).
         */
        fun discovery(
            id: SnapshotId,
            source: SourceId,
            text: DescriptionText,
            application: Application,
            at: Instant,
        ): DescriptionSnapshot =
            DescriptionSnapshot(
                id,
                source,
                text,
                SnapshotReason.DISCOVERY,
                at,
                at.takeIf { application.status.impliesApplied },
            )

        /**
         * Which snapshot of one source's [history] freezing [asOf] freezes (ADR-0046): none if the source has a
         * frozen snapshot already (only the first freeze counts, also after reopening), otherwise its newest
         * snapshot captured at or before [asOf], frozen then; none if it has no such snapshot.
         */
        fun toFreeze(
            history: List<DescriptionSnapshot>,
            asOf: Instant,
        ): DescriptionSnapshot? =
            if (history.any(DescriptionSnapshot::frozen)) {
                null
            } else {
                history
                    .filter {
                        !it.capturedAt.isAfter(
                            asOf,
                        )
                    }.maxByOrNull(DescriptionSnapshot::capturedAt)
                    ?.freeze(asOf)
            }
    }
}

/** Outcome of recording a description: a new version, or the [latest] one if the text is unchanged. */
sealed interface SnapshotRecording {
    val snapshot: DescriptionSnapshot

    data class Added(
        override val snapshot: DescriptionSnapshot,
    ) : SnapshotRecording

    data class Unchanged(
        override val snapshot: DescriptionSnapshot,
    ) : SnapshotRecording
}

/** A snapshot without its text, for the list of versions; [length] counts the text's characters. */
data class SnapshotSummary(
    val id: SnapshotId,
    val source: SourceId,
    val contentHash: ContentHash,
    val reason: SnapshotReason,
    val capturedAt: Instant,
    val frozenAt: Instant?,
    val length: Int,
)

/** Whether a [DiffSegment] is in both versions, only in the newer ([ADDED]) or only in the older ([REMOVED]). */
enum class DiffOperation { UNCHANGED, ADDED, REMOVED }

/** A run of text of a [DescriptionDiff]. [toString] leaves out the text. */
data class DiffSegment(
    val operation: DiffOperation,
    val text: String,
) {
    override fun toString(): String = "DiffSegment(operation=$operation, length=${text.length})"
}

/**
 * The difference between two snapshots of an application's descriptions (#86 computes it): the [segments]
 * in order turn [from]'s text into [to]'s (the unchanged and removed ones spell [from], the unchanged and
 * added ones [to]).
 */
data class DescriptionDiff(
    val from: SnapshotId,
    val to: SnapshotId,
    val segments: List<DiffSegment>,
)
