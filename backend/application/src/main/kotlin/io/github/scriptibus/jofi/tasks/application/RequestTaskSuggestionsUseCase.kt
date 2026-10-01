// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.applications.application.port.api.DescribeApplicationEventPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.DomainEvent
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.tasks.domain.TaskSuggestionRules

/**
 * Reacts to a committed domain event (#95): if the applications context names it (a status change, an interview
 * logged or rescheduled, `DescribeApplicationEventPort`), the worker runs the suggestion rules
 * ([TaskSuggestionRules]) once more, so a new suggestion appears and an obsolete one goes without waiting for the
 * daily run. Any other event is `null`. Queuing, not running, keeps the user's request fast and retries a failed run;
 * the run is idempotent, so several queued runs do no harm, and the daily run catches up on one lost here.
 */
class RequestTaskSuggestionsUseCase(
    private val events: DescribeApplicationEventPort,
    private val jobs: JobSchedulerPort,
) {
    fun execute(event: DomainEvent): JobResult<JobId>? =
        events.execute(event)?.let { jobs.enqueue(TaskSuggestionRules.REQUEST) }
}
