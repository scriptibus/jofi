// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.applications.application.port.api.FindSuggestionFactsPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.domain.FollowUpSuggestion
import io.github.scriptibus.jofi.tasks.domain.InterviewPreparationSuggestion
import io.github.scriptibus.jofi.tasks.domain.OfferAnswerSuggestion
import io.github.scriptibus.jofi.tasks.domain.SuggestionRun
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskSuggestionRules
import java.time.Clock

/**
 * One run of the suggestion rules of spec §10.2 ([TaskSuggestionRules], #95), daily and after the applications
 * context's events. It asks the applications context (named interface `api`) what the rules are about now, suggests
 * a follow-up, an interview preparation or an offer answer for each fact not suggested yet, and dismisses each waiting
 * suggestion of these rules whose fact is gone (the application moved on, the interview was cancelled, moved to
 * another day or has begun, the deadline changed or passed), as [reconcileSuggestions] does. Idempotent. It never
 * changes an application.
 */
class SuggestTasksUseCase(
    private val facts: FindSuggestionFactsPort,
    private val tasks: TaskRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) {
    fun execute(): TaskResult<SuggestionRun> {
        val now = clock.storedNow()
        val found =
            facts.execute(now) as? FindSuggestionFactsPort.Facts.Found
                ?: return TaskResult.StorageFailure("find suggestion facts")
        return tasks.reconcileSuggestions(wanted(found), TaskSuggestionRules.RULES, now, changelog, transactions)
    }

    private fun wanted(found: FindSuggestionFactsPort.Facts.Found): Map<TaskOrigin.Suggested, TaskDetails> {
        val followUps =
            found.followUps.map {
                FollowUpSuggestion.origin(it.application, it.silentSince) to
                    FollowUpSuggestion.details(it.application, it.title, it.dueAt)
            }
        val preparations =
            found.interviews.map {
                val day = InterviewPreparationSuggestion.dayBefore(it.startsAt, it.zone)
                InterviewPreparationSuggestion.origin(it.interview, day) to
                    InterviewPreparationSuggestion.details(it.application, it.title, day)
            }
        val answers =
            found.offers.map {
                OfferAnswerSuggestion.origin(it.application, it.answerBy) to
                    OfferAnswerSuggestion.details(it.application, it.title, it.answerBy)
            }
        return (followUps + preparations + answers).toMap()
    }
}
