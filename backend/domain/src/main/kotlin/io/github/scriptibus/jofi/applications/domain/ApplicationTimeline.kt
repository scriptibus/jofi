// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

/**
 * What a timeline entry is about (#87). Entries of the same instant are ordered by kind, the later constant first
 * (newest first), so a status change shows above the change that created the application with it. Never rename a
 * constant: cursors carry the names. [TASK] stays last, since the tasks context pages its tasks by time and id only.
 */
enum class TimelineEntryKind(
    internal val numbered: Boolean,
) {
    /** A changelog entry of the application; ids are the changelog's numbers. */
    CHANGE(true),

    /** A status change of the pipeline (ADR-0044); ids are the history's numbers. */
    STATUS_CHANGE(true),
    DESCRIPTION_SNAPSHOT(false),
    INTERVIEW(false),
    TASK(false),
}

/**
 * Where an entry sits in the timeline: newest first by [occurredAt], then by [kind] (see [TimelineEntryKind]), then
 * by [id] descending, compared as the database does (numbers numerically, UUIDs byte by byte), so a page continues
 * exactly after the entry its cursor names.
 */
data class TimelinePosition(
    val occurredAt: Instant,
    val kind: TimelineEntryKind,
    val id: String,
) : Comparable<TimelinePosition> {
    init {
        require(idIsValid(kind, id)) { "A timeline id matches its kind" }
    }

    /** The id as the number it is for [TimelineEntryKind.numbered] kinds. */
    fun number(): Long = id.toLong()

    /** The id as the UUID it is for the other kinds. */
    fun uuid(): UUID = UUID.fromString(id)

    /** Ascending, so newest first is descending. */
    override fun compareTo(other: TimelinePosition): Int =
        compareValuesBy(this, other, { it.occurredAt }, { it.kind })
            .takeIf { it != 0 }
            ?: if (kind.numbered) number().compareTo(other.number()) else compareUuids(uuid(), other.uuid())

    /** An opaque cursor for clients. */
    fun token(): String =
        ENCODER.encodeToString("$occurredAt$SEPARATOR${kind.name}$SEPARATOR$id".toByteArray(Charsets.UTF_8))

    companion object {
        private const val SEPARATOR = '|'
        private const val PARTS = 3
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        /** The position a [token] names, or null if it is no cursor this timeline gave out. */
        fun parse(token: String): TimelinePosition? {
            val parts =
                runCatching {
                    String(
                        Base64.getUrlDecoder().decode(token),
                        Charsets.UTF_8,
                    )
                }.getOrNull()?.split(SEPARATOR)
            if (parts?.size != PARTS) return null
            val occurredAt = runCatching { Instant.parse(parts[0]) }.getOrNull()
            val kind = TimelineEntryKind.entries.find { it.name == parts[1] }
            return if (occurredAt != null && kind != null && idIsValid(kind, parts[2])) {
                TimelinePosition(occurredAt, kind, parts[2])
            } else {
                null
            }
        }

        private fun idIsValid(
            kind: TimelineEntryKind,
            id: String,
        ): Boolean =
            if (kind.numbered) {
                id.toLongOrNull()?.toString() == id
            } else {
                runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
            }

        /** PostgreSQL's order of `uuid`: the 16 bytes unsigned, which [UUID.compareTo] (signed) is not. */
        private fun compareUuids(
            first: UUID,
            second: UUID,
        ): Int =
            java.lang.Long
                .compareUnsigned(first.mostSignificantBits, second.mostSignificantBits)
                .takeIf { it != 0 }
                ?: java.lang.Long.compareUnsigned(first.leastSignificantBits, second.leastSignificantBits)
    }
}

/**
 * One entry of an application's timeline (#87), a read model over what other parts record. Entries carry no free
 * text beyond what their own API shows: a change names its fields but no values, a status change has no reason, an
 * interview no notes. [toString] of a task leaves out its title.
 */
sealed interface TimelineEntry {
    val occurredAt: Instant
    val position: TimelinePosition

    /** A changelog entry of the application, by [actor], naming the [fields] with values it changed. */
    data class Change(
        val id: Long,
        override val occurredAt: Instant,
        val actor: Actor,
        val fields: List<String>,
    ) : TimelineEntry {
        override val position get() = TimelinePosition(occurredAt, TimelineEntryKind.CHANGE, id.toString())
    }

    /** [actor] moved the application [from] one status [to] another; [from] is absent for the first entry. */
    data class StatusChanged(
        val id: Long,
        override val occurredAt: Instant,
        val actor: Actor,
        val from: ApplicationStatus?,
        val to: ApplicationStatus,
        val declineCategory: DeclineCategory?,
    ) : TimelineEntry {
        override val position get() = TimelinePosition(occurredAt, TimelineEntryKind.STATUS_CHANGE, id.toString())
    }

    /** A job description of [source] was captured (at [occurredAt]) for [reason], frozen at [frozenAt] if so. */
    data class DescriptionCaptured(
        val id: SnapshotId,
        override val occurredAt: Instant,
        val source: SourceId,
        val reason: SnapshotReason,
        val frozenAt: Instant?,
    ) : TimelineEntry {
        override val position
            get() = TimelinePosition(occurredAt, TimelineEntryKind.DESCRIPTION_SNAPSHOT, id.value.toString())
    }

    /** An interview starting at [occurredAt] (ADR-0048), planned in [timeZone], with its [outcome] once known. */
    data class InterviewPlanned(
        val id: InterviewId,
        override val occurredAt: Instant,
        val type: InterviewType,
        val timeZone: ZoneId,
        val outcome: InterviewOutcome?,
    ) : TimelineEntry {
        override val position get() = TimelinePosition(occurredAt, TimelineEntryKind.INTERVIEW, id.value.toString())
    }

    /** A task linked to the application, added at [occurredAt]; [completedAt] is set while it is done. */
    data class TaskAdded(
        val id: UUID,
        override val occurredAt: Instant,
        val title: String,
        val completedAt: Instant?,
    ) : TimelineEntry {
        override val position get() = TimelinePosition(occurredAt, TimelineEntryKind.TASK, id.toString())

        override fun toString(): String = "TaskAdded(id=$id, occurredAt=$occurredAt, completedAt=$completedAt)"
    }
}

/** Up to [limit] entries, newest first, after the entry at [before] (from the newest one without). */
data class TimelineQuery(
    val before: TimelinePosition? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "A timeline page holds 1 to $MAX_LIMIT entries" }
    }

    /** How many entries each source reads: one more than a page shows, to know whether there is a next page. */
    val fetchSize: Int get() = limit + 1

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
    }
}

/** One page of the timeline, newest first; [next] is where the following page starts, absent on the last one. */
data class TimelinePage(
    val entries: List<TimelineEntry>,
    val next: TimelinePosition?,
) {
    companion object {
        /**
         * The page of [query] from what each source read for it (at most [TimelineQuery.fetchSize] entries each,
         * the newest after the cursor): merged newest first and cut to the limit.
         */
        fun merge(
            query: TimelineQuery,
            vararg sources: List<TimelineEntry>,
        ): TimelinePage {
            val merged = sources.flatMap { it }.sortedByDescending { it.position }
            val page = merged.take(query.limit)
            return TimelinePage(page, if (merged.size > query.limit) page.last().position else null)
        }
    }
}
