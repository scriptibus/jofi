// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.DomainEvent
import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.time.Instant
import java.util.UUID

/** Identifies one interview or call. */
@JvmInline
value class InterviewId(
    val value: UUID,
) {
    /** How changelog entries refer to this interview (entity type [ENTITY_TYPE]). */
    fun toEntityRef(): EntityRef = EntityRef(ENTITY_TYPE, value.toString())

    companion object {
        /** The changelog entity type of interviews and calls; never rename it, stored entries use it. */
        const val ENTITY_TYPE = "interview"
    }
}

/** What kind of interview or call it is (spec §6.1). Never rename a constant: the database stores the names. */
enum class InterviewType {
    PHONE_SCREEN,
    HR,
    TECHNICAL,
    CASE,
    ON_SITE,
    FINAL,

    /** A call or meeting none of the others fits, e.g. talking about the offer. */
    OTHER,
}

/** How an interview or call ended for the user; none while it is still to come or undecided. */
enum class InterviewOutcome {
    /** The user moved on: a next round or an offer. */
    PASSED,

    /** The company said no after it. */
    REJECTED,

    /** The user pulled out after it. */
    WITHDRAWN,

    /** It did not take place. */
    CANCELLED,
}

/**
 * An interview or call of an application (spec §6.1), logged by the user, the AI or an external client. It is an
 * aggregate of its own, not a version of the application: logging or editing one keeps the application's `version`,
 * and it has its own [version], so edits by the user, the AI and external clients cannot overwrite each other. It goes
 * with its application (`ON DELETE CASCADE`); a deleted contact leaves its participants (spec §5, ADR-0041).
 *
 * Training sessions (M5) will link to it through a table of their own; until then it has no link (the placeholder
 * the spec's "link to training sessions" asks for). [toString] shows neither notes nor participants.
 */
data class Interview(
    val id: InterviewId,
    val application: ApplicationId,
    val details: InterviewDetails,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(version >= INITIAL_VERSION) { "An interview version must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "An interview cannot be updated before it was created" }
    }

    /**
     * The interview with new [details], by [actor] [at]: [InterviewEdit.Unchanged] if nothing changes, otherwise a
     * new version, with [InterviewRescheduled] if it now starts at another instant. Callers check the client's
     * version before.
     */
    fun edit(
        details: InterviewDetails,
        actor: Actor,
        at: Instant,
    ): InterviewEdit {
        if (details == this.details) return InterviewEdit.Unchanged
        val edited = copy(details = details, version = version + 1, updatedAt = at)
        val before = this.details.time
        val rescheduled =
            if (details.time.startsAt != before.startsAt) {
                InterviewRescheduled(id, application, before, details.time, actor, at)
            } else {
                null
            }
        return InterviewEdit.Changed(edited, rescheduled)
    }

    override fun toString(): String = "Interview(id=${id.value}, application=${application.value}, version=$version)"

    companion object {
        const val INITIAL_VERSION = 0L

        /** The confirmable operation (ADR-0039) of deleting interviews; its targets are interview ids. */
        const val DELETE_OPERATION = "interviews.delete"

        /** The most upcoming interviews one list shows, soonest first. */
        const val MAX_UPCOMING = 100

        /** A new interview of [application] with [details], logged by [actor] [at], and its [InterviewScheduled]. */
        fun log(
            id: InterviewId,
            application: ApplicationId,
            details: InterviewDetails,
            actor: Actor,
            at: Instant,
        ): InterviewLogged =
            InterviewLogged(
                Interview(id, application, details, INITIAL_VERSION, at, at),
                InterviewScheduled(id, application, details.type, details.time, actor, at),
            )
    }
}

/** A new interview to store and the event to publish once the store accepted it ([Interview.log]). */
data class InterviewLogged(
    val interview: Interview,
    val event: InterviewScheduled,
)

/** Outcome of [Interview.edit]. */
sealed interface InterviewEdit {
    /** The new version to store and, if its start moved, the event to publish once the store accepted it. */
    data class Changed(
        val interview: Interview,
        val rescheduled: InterviewRescheduled?,
    ) : InterviewEdit

    /** The details are the stored ones: nothing to store, no changelog entry, no event. */
    data object Unchanged : InterviewEdit
}

/**
 * Domain event: [actor] logged [interview] of [application], a [type] at [time], at [occurredAt] (the task
 * suggestions, #95, prepare for it). It carries no participants and no notes.
 */
data class InterviewScheduled(
    val interview: InterviewId,
    val application: ApplicationId,
    val type: InterviewType,
    val time: InterviewTime,
    val actor: Actor,
    val occurredAt: Instant,
) : DomainEvent

/** Domain event: [actor] moved [interview] of [application] [from] one start [to] another at [occurredAt]. */
data class InterviewRescheduled(
    val interview: InterviewId,
    val application: ApplicationId,
    val from: InterviewTime,
    val to: InterviewTime,
    val actor: Actor,
    val occurredAt: Instant,
) : DomainEvent

/**
 * An interview still to come, with the title of its application, for the list across applications (#92) and the
 * dashboard's countdown. [toString] shows neither the title nor the interview's notes.
 */
data class UpcomingInterview(
    val interview: Interview,
    val applicationTitle: String,
) {
    override fun toString(): String = "UpcomingInterview(interview=$interview)"
}
