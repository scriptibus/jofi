// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import java.time.Instant

/**
 * An interview as a list shows it (ADR-0056): everything but the two notes, of which only an excerpt is kept. It has
 * no notes fields, so a list entry cannot be mistaken for the interview itself; reading one interview gives the whole
 * text. [toString] shows neither notes nor participants.
 */
data class InterviewSummary(
    val id: InterviewId,
    val application: ApplicationId,
    val type: InterviewType,
    val time: InterviewTime,
    val participants: Set<ContactRef>,
    val outcome: InterviewOutcome?,
    val preparationNotesExcerpt: TextExcerpt?,
    val notesExcerpt: TextExcerpt?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String =
        "InterviewSummary(id=${id.value}, application=${application.value}, version=$version)"

    companion object {
        /** The excerpts are cut from [preparationNotes] and [notes]: the interview's own, or after the AI's filter. */
        fun of(
            interview: Interview,
            preparationNotes: String? = interview.details.preparationNotes,
            notes: String? = interview.details.notes,
        ): InterviewSummary {
            val details = interview.details
            return InterviewSummary(
                interview.id,
                interview.application,
                details.type,
                details.time,
                details.participants,
                details.outcome,
                TextExcerpt.ofOrNull(preparationNotes),
                TextExcerpt.ofOrNull(notes),
                interview.version,
                interview.createdAt,
                interview.updatedAt,
            )
        }
    }
}
