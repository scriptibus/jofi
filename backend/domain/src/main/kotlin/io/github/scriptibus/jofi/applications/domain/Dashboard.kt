// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.time.Instant

/**
 * A stage of the dashboard's funnel (spec §10.1, ADR-0052): an application has reached it once its status history
 * holds any of [statuses]. Each stage's statuses contain the next one's, so the funnel never widens.
 */
enum class FunnelStage(
    val statuses: Set<ApplicationStatus>,
) {
    /** Every status that implies having applied ([ApplicationStatus.impliesApplied]). */
    APPLIED(ApplicationStatus.entries.filter { it.impliesApplied }.toSet()),

    /** Interviewing, or past it: an offer, accepted or not. */
    INTERVIEW(setOf(ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFER, ApplicationStatus.ACCEPTED)),

    OFFER(setOf(ApplicationStatus.OFFER, ApplicationStatus.ACCEPTED)),
    ;

    companion object {
        /** The statuses only an answer from the company leads to: an interview, an offer or a rejection. */
        val RESPONSE: Set<ApplicationStatus> = INTERVIEW.statuses + ApplicationStatus.REJECTED
    }
}

/**
 * How many applications ever reached each [FunnelStage], and how many of the applied ones got a response
 * ([FunnelStage.RESPONSE]); counted over the status history, so a reopened application still counts (ADR-0052).
 */
data class ApplicationFunnel(
    val applied: Long,
    val interviewed: Long,
    val offered: Long,
    val responded: Long,
) {
    init {
        require(offered >= 0) { "Counts are not negative" }
        require(offered <= interviewed && interviewed <= applied) { "A later stage is part of the earlier one" }
        require(responded in interviewed..applied) { "Interviews are responses to applications" }
    }

    /** The share of applications that led to an interview, absent before the first application. */
    val interviewRate: Double? get() = share(interviewed, applied)

    /** The share of interviewed applications that led to an offer, absent before the first interview. */
    val offerRate: Double? get() = share(offered, interviewed)

    /** The share of applications the company answered, absent before the first application. */
    val responseRate: Double? get() = share(responded, applied)

    private fun share(
        part: Long,
        whole: Long,
    ): Double? = if (whole == 0L) null else part.toDouble() / whole

    companion object {
        val NONE = ApplicationFunnel(0, 0, 0, 0)
    }
}

/** The dashboard's pipeline figures: applications per current status (every status, zero ones too), unread, funnel. */
data class PipelineOverview(
    val byStatus: Map<ApplicationStatus, Long>,
    val unread: Long,
    val funnel: ApplicationFunnel,
) {
    init {
        require(byStatus.keys == ApplicationStatus.entries.toSet()) { "Every status has a count" }
        require(unread in 0..byStatus.values.sum()) { "Unread applications are applications" }
    }

    companion object {
        /** The overview from the [counted] statuses; a status without applications counts zero. */
        fun of(
            counted: Map<ApplicationStatus, Long>,
            unread: Long,
            funnel: ApplicationFunnel,
        ): PipelineOverview =
            PipelineOverview(ApplicationStatus.entries.associateWith { counted[it] ?: 0 }, unread, funnel)
    }
}

/**
 * One changelog entry of the dashboard's recent activity (ADR-0052): who ([actor]) changed which [entity], the
 * entry's fixed [description] and the names of the changed [fields], never their values or the reason. [application]
 * is the application the entry is about (it, its interview, source or description), while it exists.
 */
data class ActivityEntry(
    val id: Long,
    val occurredAt: Instant,
    val actor: Actor,
    val entity: EntityRef,
    val description: String,
    val fields: List<String>,
    val application: ActivityApplication?,
)

/** The application an [ActivityEntry] is about, labelled with its job title as the application list shows it. */
data class ActivityApplication(
    val id: ApplicationId,
    val title: String,
)

/** The [limit] newest entries of the recent activity. */
data class ActivityQuery(
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "Recent activity holds 1 to $MAX_LIMIT entries" }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100

        /**
         * The changelog entity types of the job search the activity shows: applications and what belongs to them,
         * companies, contacts, tasks and countdowns. Settings, saved views, AI setup and system entries (sessions,
         * backups, passwords) are housekeeping, not activity. The types of other contexts are their changelog
         * names, which are never renamed.
         */
        val ENTITY_TYPES: Set<String> =
            setOf(
                ApplicationId.ENTITY_TYPE,
                SourceId.ENTITY_TYPE,
                SnapshotId.ENTITY_TYPE,
                InterviewId.ENTITY_TYPE,
                "company",
                "contact",
                "task",
                "countdown",
            )
    }
}
