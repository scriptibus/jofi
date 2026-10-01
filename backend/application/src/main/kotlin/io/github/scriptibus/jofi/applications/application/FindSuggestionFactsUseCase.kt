// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationActivityRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationSettingsRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.api.FindSuggestionFactsPort
import io.github.scriptibus.jofi.applications.application.port.api.FindSuggestionFactsPort.Facts
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.Interview
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The facts of the task suggestions (#95) as of an instant: `APPLIED` applications silent for the follow-up period of
 * the settings (ADR-0050; a day is 24 hours, so the period needs no zone), the interviews still to come (the next
 * [Interview.MAX_UPCOMING]), and the offers whose answer date is today (UTC) or later (the first
 * [ApplicationSearch.MAX_SIZE] offers). Reads only; changes nothing.
 */
class FindSuggestionFactsUseCase(
    private val settings: ApplicationSettingsRepositoryPort,
    private val activity: ApplicationActivityRepositoryPort,
    private val interviews: InterviewRepositoryPort,
    private val applications: ApplicationRepositoryPort,
) : FindSuggestionFactsPort {
    override fun execute(at: Instant): Facts {
        val followUps = followUps(at)
        val ahead = interviewsAhead(at)
        val offers = offers(at)
        return if (followUps == null || ahead == null || offers == null) {
            Facts.Unavailable
        } else {
            Facts.Found(followUps, ahead, offers)
        }
    }

    private fun interviewsAhead(at: Instant): List<FindSuggestionFactsPort.InterviewAhead>? {
        val upcoming = interviews.upcoming(at, Interview.MAX_UPCOMING) as? ApplicationStoreResult.Success
        return upcoming?.value?.map {
            val time = it.interview.details.time
            FindSuggestionFactsPort.InterviewAhead(
                it.interview.id.value,
                it.interview.application.value,
                it.applicationTitle,
                time.startsAt,
                time.zone,
            )
        }
    }

    private fun followUps(at: Instant): List<FindSuggestionFactsPort.FollowUpDue>? {
        val current = settings.find() as? ApplicationStoreResult.Success ?: return null
        val days = current.value.values.followUpAfterDays
        val period = Duration.ofDays(days.toLong())
        val silent = activity.silentSince(at.minus(period), FOLLOW_UP_STATUSES) as? ApplicationStoreResult.Success
        return silent?.value?.map {
            FindSuggestionFactsPort.FollowUpDue(it.id, it.title, it.silentSince, it.silentSince.plus(period))
        }
    }

    private fun offers(at: Instant): List<FindSuggestionFactsPort.OfferOpen>? {
        val today = at.atZone(ZoneOffset.UTC).toLocalDate()
        val search = ApplicationSearch(statuses = setOf(ApplicationStatus.OFFER), size = ApplicationSearch.MAX_SIZE)
        val found = applications.search(search) as? ApplicationStoreResult.Success ?: return null
        return found.value.items.mapNotNull { application ->
            application.details.offer
                ?.answerBy
                ?.takeUnless { it.isBefore(today) }
                ?.let { FindSuggestionFactsPort.OfferOpen(application.id.value, application.details.title, it) }
        }
    }

    private companion object {
        /** The follow-up is for an application waiting for its first answer (spec §6.1: "after Applied"). */
        val FOLLOW_UP_STATUSES = setOf(ApplicationStatus.APPLIED)
    }
}
