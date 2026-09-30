// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.api

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * What the task suggestions of spec §10.2 are about (#95), asked by the tasks context: applications to follow up on,
 * interviews to prepare for and offers to answer, as of the given instant. Part of the named interface `api`: only
 * plain values cross it. Reads only, never throws. [toString] of each fact leaves out the job title.
 */
interface FindSuggestionFactsPort {
    fun execute(at: Instant): Facts

    /** Outcome of [execute]. A sealed class, since every interface in a port package is a port. */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Facts {
        data class Found(
            val followUps: List<FollowUpDue>,
            val interviews: List<InterviewAhead>,
            val offers: List<OfferOpen>,
        ) : Facts()

        /** The settings or the applications could not be read. */
        data object Unavailable : Facts()
    }

    /**
     * An `APPLIED` application without activity (as `FindGhostedCandidatesPort` defines it) since [silentSince], for
     * at least the follow-up period of the settings, which ended at [dueAt].
     */
    data class FollowUpDue(
        val application: UUID,
        val title: String,
        val silentSince: Instant,
        val dueAt: Instant,
    ) {
        override fun toString(): String = "FollowUpDue(application=$application, silentSince=$silentSince)"
    }

    /** An interview that is not cancelled and starts at or after the instant asked for, planned in [zone]. */
    data class InterviewAhead(
        val interview: UUID,
        val application: UUID,
        val title: String,
        val startsAt: Instant,
        val zone: ZoneId,
    ) {
        override fun toString(): String = "InterviewAhead(interview=$interview, startsAt=$startsAt, zone=$zone)"
    }

    /** An application at `OFFER` whose offer is to be answered by [answerBy], not before the day asked for (UTC). */
    data class OfferOpen(
        val application: UUID,
        val title: String,
        val answerBy: LocalDate,
    ) {
        override fun toString(): String = "OfferOpen(application=$application, answerBy=$answerBy)"
    }
}
