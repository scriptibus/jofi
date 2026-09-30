// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.textProblem
import java.time.LocalDate

/**
 * What a company offered (spec §6.1); the offer comparison is M6. At least one field is set. Text
 * fields are the user's notes, so [toString] leaves them out.
 */
data class OfferDetails(
    val salary: Pay? = null,
    /** Bonus, equity or other variable pay, as the offer describes it. */
    val bonus: String? = null,
    val benefits: String? = null,
    val remoteShare: RemoteShare? = null,
    val vacationDays: Int? = null,
    /** The notice period, as the offer states it ("3 months to the end of a quarter"). */
    val noticePeriod: String? = null,
    val startDate: LocalDate? = null,
    /** The date by which the company wants an answer. */
    val answerBy: LocalDate? = null,
) {
    init {
        require(
            listOf(salary, bonus, benefits, remoteShare, vacationDays, noticePeriod, startDate, answerBy).any {
                it !=
                    null
            },
        ) {
            "An offer names at least one detail"
        }
        require(bonus == null || textProblem(bonus, MAX_TEXT_LENGTH) == null) { "An offer bonus breaks an invariant" }
        require(
            benefits == null || textProblem(benefits, MAX_TEXT_LENGTH) == null,
        ) { "Offer benefits break an invariant" }
        require(noticePeriod == null || textProblem(noticePeriod, MAX_NOTICE_PERIOD_LENGTH) == null) {
            "An offer notice period breaks an invariant"
        }
        require(
            vacationDays == null || vacationDays in 0..MAX_VACATION_DAYS,
        ) { "Vacation days are 0 to $MAX_VACATION_DAYS" }
    }

    override fun toString(): String = "OfferDetails(startDate=$startDate, answerBy=$answerBy)"

    companion object {
        const val MAX_TEXT_LENGTH = 5_000
        const val MAX_NOTICE_PERIOD_LENGTH = 200
        const val MAX_VACATION_DAYS = 366
    }
}
