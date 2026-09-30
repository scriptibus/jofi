// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.textProblem
import java.time.LocalDate

/**
 * What the user (or the AI, a scanner or an external client) records about a job (spec §6.1). Build it
 * from untrusted input with [ApplicationInput.validate], which reports every problem as a value; the
 * constructor only guards invariants and throws on a programming error. Notes are the user's own
 * words, so [toString] leaves them out. The decline reason belongs to the status, not to the details
 * ([Application.declineReason], ADR-0044).
 */
data class ApplicationDetails(
    val title: String,
    val company: CompanyRef,
    /** Where the job is, as the posting says it ("Berlin", "Remote (EU)"). */
    val location: String? = null,
    val remoteShare: RemoteShare? = null,
    val employmentType: EmploymentType? = null,
    val seniority: Seniority? = null,
    /** The application deadline, if the posting names one. */
    val deadline: LocalDate? = null,
    val howApplied: HowApplied? = null,
    /** Notes on the portal or channel used (account, reference number), Markdown. */
    val portalNotes: String? = null,
    val payBand: PayBand? = null,
    val languageAndTone: LanguageAndTone = LanguageAndTone.UNKNOWN,
    /** What the company offered, once the application reached an offer. */
    val offer: OfferDetails? = null,
) {
    init {
        require(textProblem(title, MAX_TITLE_LENGTH) == null) { "An application title breaks an invariant" }
        require(location == null || textProblem(location, MAX_LOCATION_LENGTH) == null) {
            "An application location breaks an invariant"
        }
        require(portalNotes == null || textProblem(portalNotes, MAX_NOTES_LENGTH) == null) {
            "Portal notes break an invariant"
        }
    }

    override fun toString(): String = "ApplicationDetails(company=${company.value})"

    companion object {
        const val MAX_TITLE_LENGTH = 300
        const val MAX_LOCATION_LENGTH = 200
        const val MAX_NOTES_LENGTH = 50_000
    }
}

/** How much of the work can be done remotely, in percent (0 on-site only, 100 fully remote). */
@JvmInline
value class RemoteShare(
    val percent: Int,
) {
    init {
        require(percent in 0..MAX_PERCENT) { "A remote share is 0 to $MAX_PERCENT percent" }
    }

    companion object {
        const val MAX_PERCENT = 100
    }
}

enum class EmploymentType {
    FULL_TIME,
    PART_TIME,

    /** Fixed-term or contract employment. */
    CONTRACT,

    /** Self-employed, e.g. a freelance project. */
    FREELANCE,
    INTERNSHIP,

    /** A job next to university studies (Werkstudent). */
    WORKING_STUDENT,
    APPRENTICESHIP,
    OTHER,
}

enum class Seniority {
    ENTRY,
    JUNIOR,
    MID,
    SENIOR,
    LEAD,
    PRINCIPAL,

    /** Head of, director and above. */
    EXECUTIVE,
}

/** How the user applied (spec §6.1). */
enum class HowApplied { PORTAL, EMAIL, REFERRAL, OTHER }

/**
 * Why an application ended without a new job (spec §6.1): the user decided against it ("Declined")
 * or the company rejected them ("Rejected"). A status change to either sets it (ADR-0044).
 */
data class DeclineReason(
    val category: DeclineCategory,
    /** The user's own words, Markdown. */
    val text: String? = null,
) {
    init {
        require(text == null || textProblem(text, MAX_TEXT_LENGTH) == null) { "A decline reason breaks an invariant" }
    }

    override fun toString(): String = "DeclineReason(category=$category)"

    companion object {
        const val MAX_TEXT_LENGTH = 5_000
    }
}

enum class DeclineCategory {
    SALARY,
    LOCATION,

    /** Too little (or too much) remote work. */
    REMOTE_POLICY,

    /** The role itself: tasks, responsibility, level. */
    ROLE,

    /** The company: culture, product, reputation. */
    COMPANY,

    /** Skills or experience did not match. */
    SKILLS,
    TIMING,

    /** The user took another offer. */
    OTHER_OFFER,
    POSITION_FILLED,

    /** The company gave no reason. */
    NO_REASON_GIVEN,
    OTHER,
}
