// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.text.textProblem
import java.time.Instant

/**
 * Where an application stands (spec §6.2): the pipeline `DISCOVERED` to `OFFER`, then one of the
 * terminal statuses. A State pattern: each status decides which statuses it may move to ([canMoveTo]),
 * in one exhaustive `when`, so a new status cannot be added without deciding its moves. The matrix and
 * its reasons are ADR-0044. Never rename a constant: the database and the history store the names.
 */
enum class ApplicationStatus {
    DISCOVERED,
    SHORTLISTED,
    PREPARING,
    APPLIED,
    INTERVIEWING,
    OFFER,

    /** The user accepted the offer. */
    ACCEPTED,

    /** The company said no. */
    REJECTED,

    /** The user pulled out after applying. */
    WITHDRAWN,

    /** The user decided against the job, before applying or on the offer. */
    DECLINED,

    /** No answer for a long time after applying (suggested after 14 weeks, #85). */
    GHOSTED,
    ;

    /** Ended, not in the pipeline; still reopenable (ADR-0044). */
    val isTerminal: Boolean get() = this !in PIPELINE

    /** A move here needs a [DeclineReason] (a category, the text optional), which the application then holds. */
    val takesDeclineReason: Boolean get() = this == DECLINED || this == REJECTED

    /**
     * Whether an application in this status may move to [target]. The pipeline moves freely (forward
     * skips and backward corrections); moving to the same status is only a transition for [DECLINED] and
     * [REJECTED], where it corrects the reason.
     */
    fun canMoveTo(target: ApplicationStatus): Boolean = target in successors()

    private fun successors(): Set<ApplicationStatus> =
        when (this) {
            DISCOVERED, SHORTLISTED, PREPARING -> PIPELINE - this + DECLINED
            APPLIED, INTERVIEWING -> PIPELINE - this + setOf(REJECTED, WITHDRAWN, GHOSTED)
            OFFER -> PIPELINE - this + setOf(ACCEPTED, REJECTED, DECLINED)
            ACCEPTED -> setOf(OFFER)
            REJECTED -> setOf(REJECTED, APPLIED, INTERVIEWING, OFFER)
            WITHDRAWN -> setOf(APPLIED, INTERVIEWING)
            DECLINED -> setOf(DECLINED, DISCOVERED, SHORTLISTED, PREPARING, OFFER)
            GHOSTED -> setOf(APPLIED, INTERVIEWING, OFFER, REJECTED, WITHDRAWN)
        }

    companion object {
        /** The status of a new application. */
        val INITIAL = DISCOVERED

        private val PIPELINE = setOf(DISCOVERED, SHORTLISTED, PREPARING, APPLIED, INTERVIEWING, OFFER)
    }
}

/**
 * A status change as entered: the target [status], an optional [reason] (why; free text, Markdown) and,
 * exactly for `DECLINED` and `REJECTED`, the [declineCategory]. [validate] normalizes the reason like
 * every other text (NFC, trimmed, blank is absent). [toString] leaves out the reason.
 */
data class StatusChangeInput(
    val status: ApplicationStatus,
    val reason: String? = null,
    val declineCategory: DeclineCategory? = null,
) {
    fun validate(): ApplicationValidation<StatusChangeRequest> {
        val checks = InputChecks()
        val reason = checks.text(ApplicationField.STATUS_REASON, reason, StatusChange.MAX_REASON_LENGTH)
        if (status.takesDeclineReason && declineCategory == null) {
            checks.report(ApplicationField.DECLINE_CATEGORY, ApplicationProblem.REQUIRED)
        }
        if (!status.takesDeclineReason && declineCategory != null) {
            checks.report(ApplicationField.DECLINE_CATEGORY, ApplicationProblem.NOT_APPLICABLE)
        }
        if (checks.count > 0) return ApplicationValidation.Invalid(checks.violations)
        return ApplicationValidation.Valid(StatusChangeRequest(status, reason, declineCategory))
    }

    override fun toString(): String = "StatusChangeInput(status=$status, declineCategory=$declineCategory)"
}

/** A valid status change request; whether the application may make the move is [Application.changeStatus]'s call. */
data class StatusChangeRequest(
    val status: ApplicationStatus,
    val reason: String? = null,
    val declineCategory: DeclineCategory? = null,
) {
    init {
        require(reason == null || textProblem(reason, StatusChange.MAX_REASON_LENGTH) == null) {
            "A status change reason breaks an invariant"
        }
        require((declineCategory != null) == status.takesDeclineReason) {
            "A decline category belongs to exactly the statuses that take a decline reason"
        }
    }

    /** The reason the application holds after the move: the category with the text, or none. */
    val declineReason: DeclineReason? get() = declineCategory?.let { DeclineReason(it, reason) }

    override fun toString(): String = "StatusChangeRequest(status=$status, declineCategory=$declineCategory)"
}

/**
 * One entry of an application's status history (spec §6.2): [actor] moved it [from] one status [to]
 * another [at], optionally saying why ([reason]); `DECLINED` and `REJECTED` entries keep their
 * [declineCategory] and reason, so they survive a reopening that clears the application's own. [from] is
 * `null` only for the first entry, the status the application started with. [toString] leaves out the reason.
 */
data class StatusChange(
    val application: ApplicationId,
    val from: ApplicationStatus?,
    val to: ApplicationStatus,
    val reason: String?,
    val declineCategory: DeclineCategory?,
    val actor: Actor,
    val at: Instant,
) {
    init {
        require(reason == null || textProblem(reason, MAX_REASON_LENGTH) == null) {
            "A status change reason breaks an invariant"
        }
        require((declineCategory != null) == to.takesDeclineReason) {
            "A decline category belongs to exactly the statuses that take a decline reason"
        }
    }

    override fun toString(): String =
        "StatusChange(application=${application.value}, from=$from, to=$to, actor=$actor, at=$at)"

    companion object {
        /** As long as a decline reason, which a `DECLINED` or `REJECTED` entry copies. */
        const val MAX_REASON_LENGTH = DeclineReason.MAX_TEXT_LENGTH

        /** The first entry of [application]'s history: the status it was created in, by [actor]. */
        fun initial(
            application: Application,
            actor: Actor,
        ): StatusChange =
            StatusChange(
                application.id,
                null,
                application.status,
                application.declineReason?.text,
                application.declineReason?.category,
                actor,
                application.createdAt,
            )
    }
}

/** Outcome of [Application.changeStatus]. */
sealed interface StatusTransition {
    /** The moved [application] (a new version), the history entry to append and the event to publish. */
    data class Changed(
        val application: Application,
        val change: StatusChange,
        val event: ApplicationStatusChanged,
    ) : StatusTransition

    /** The application already is in the status, with the same decline reason: nothing to store. */
    data object Unchanged : StatusTransition

    /** The matrix (ADR-0044) has no move [from] this status [to] that one. */
    data class NotAllowed(
        val from: ApplicationStatus,
        val to: ApplicationStatus,
    ) : StatusTransition
}

/**
 * Domain event: [actor] moved [application] [from] one status [to] another at [occurredAt] (the Ghosted
 * suggestion, #85, and the views react to it). It carries no reason, since reasons are free text.
 */
data class ApplicationStatusChanged(
    val application: ApplicationId,
    val from: ApplicationStatus,
    val to: ApplicationStatus,
    val actor: Actor,
    val occurredAt: Instant,
)
