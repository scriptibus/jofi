// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.api

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.InterviewId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * What the dashboard countdowns of spec §10.1 count down to (#112), asked by the tasks context: the next interview
 * still to come, the application deadlines and the offer answer deadlines. Part of the named interface `api`: only
 * plain values cross it. Reads only, never throws. [toString] of each fact leaves out the job title.
 */
interface FindCountdownFactsPort {
    /**
     * The facts as of the instant [at], with [today] the first day that counts (the viewer's, which the caller knows).
     * At most [MAX_PER_KIND] deadlines and offer answers each, soonest first.
     */
    fun execute(
        at: Instant,
        today: LocalDate,
    ): Facts

    /** Outcome of [execute]. A sealed class, since every interface in a port package is a port. */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Facts {
        /**
         * [nextInterview]: the interview that is not cancelled and starts soonest at or after the instant asked for,
         * if any. [deadlines]: the application deadlines from today on of applications not yet applied for and still
         * in the pipeline (`DISCOVERED`, `SHORTLISTED`, `PREPARING`); a deadline is pointless once the user applied.
         * [offerAnswers]: the dates from today on by which an offer (an application at `OFFER`) is to be answered.
         */
        data class Found(
            val nextInterview: NextInterview?,
            val deadlines: List<DueDate>,
            val offerAnswers: List<DueDate>,
        ) : Facts()

        /** The interviews or the applications could not be read. */
        data object Unavailable : Facts()
    }

    /** An interview of [application] (job [title]) starting at [startsAt], planned in [zone] (ADR-0048). */
    data class NextInterview(
        val interview: UUID,
        val application: UUID,
        val title: String,
        val startsAt: Instant,
        val zone: ZoneId,
    ) {
        override fun toString(): String = "NextInterview(interview=$interview, startsAt=$startsAt, zone=$zone)"
    }

    /** A day of [application] (job [title]): its deadline or its offer's answer date. */
    data class DueDate(
        val application: UUID,
        val title: String,
        val date: LocalDate,
    ) {
        override fun toString(): String = "DueDate(application=$application, date=$date)"
    }

    companion object {
        /** The most deadlines and offer answers [execute] returns of each kind. */
        const val MAX_PER_KIND = 50

        /** How other contexts name an application they link to (its changelog entity type). */
        const val APPLICATION_ENTITY_TYPE = ApplicationId.ENTITY_TYPE

        /** How other contexts name an interview they link to (its changelog entity type). */
        const val INTERVIEW_ENTITY_TYPE = InterviewId.ENTITY_TYPE
    }
}
