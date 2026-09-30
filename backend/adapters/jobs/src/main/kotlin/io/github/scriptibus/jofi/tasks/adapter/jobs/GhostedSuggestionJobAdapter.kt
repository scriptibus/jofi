// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.jobs

import io.github.scriptibus.jofi.shared.application.port.JobHandlerPort
import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.github.scriptibus.jofi.tasks.application.SuggestGhostedApplicationsUseCase
import io.github.scriptibus.jofi.tasks.domain.GhostedSuggestion
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import org.springframework.stereotype.Component

/**
 * Runs the daily [GhostedSuggestion] in the worker. A failed run is retried; since the run is idempotent, a retry
 * only does what the failed attempt left undone.
 */
@Component
class GhostedSuggestionJobAdapter(
    private val suggest: SuggestGhostedApplicationsUseCase,
) : JobHandlerPort {
    override val type: JobType = GhostedSuggestion.TYPE

    override fun run(arguments: Map<String, String>): JobOutcome =
        when (suggest.execute()) {
            is TaskResult.Success -> JobOutcome.Done

            is TaskResult.StorageFailure -> JobOutcome.Retry(STORAGE_FAILURE)

            // The user changed or deleted a suggestion while the run dismissed it; the next attempt reads it again.
            is TaskResult.Failure -> JobOutcome.Retry(SUGGESTION_CHANGED)
        }

    private companion object {
        val STORAGE_FAILURE = FailureReason("storage-failure")
        val SUGGESTION_CHANGED = FailureReason("suggestion-changed")
    }
}
