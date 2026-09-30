// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.api

import java.time.Instant
import java.util.UUID

/**
 * The applications without an answer for the Ghosted period (spec §6.2, ADR-0044, #85), asked by the tasks context,
 * which suggests marking them Ghosted. Part of the named interface `api` of the applications context: what other
 * contexts may call. Only plain values cross it, so callers never see the applications domain. Reads only, never
 * throws.
 *
 * An application is a candidate when it is `APPLIED` or `INTERVIEWING` and its last activity lies at least
 * `ghostedAfterWeeks` (the settings, ADR-0050) before the given instant. Its last activity is the latest of: its last
 * status change (any move, also between `APPLIED` and `INTERVIEWING`), the creation, last edit or start of any of its
 * interviews (an interview still to come keeps it active), and the last change of its contact links. Detail edits,
 * the unread flag, scores, sources and scanner checks are no answer from the company and do not count.
 */
interface FindGhostedCandidatesPort {
    /** The candidates as of [at], longest silent first. */
    fun execute(at: Instant): Candidates

    /** Outcome of [execute]. A sealed class, since every interface in a port package is a port. */
    @Suppress("AbstractClassCanBeInterface")
    sealed class Candidates {
        data class Found(
            val candidates: List<Candidate>,
        ) : Candidates()

        /** The settings or the applications could not be read. */
        data object Unavailable : Candidates()
    }

    /**
     * One silent application: its [id], its [title] (the job title, which the suggestion shows) and its last activity
     * [silentSince]. A new activity moves [silentSince], so it tells one silence from the next.
     */
    data class Candidate(
        val id: UUID,
        val title: String,
        val silentSince: Instant,
    ) {
        override fun toString(): String = "Candidate(id=$id, silentSince=$silentSince)"
    }
}
