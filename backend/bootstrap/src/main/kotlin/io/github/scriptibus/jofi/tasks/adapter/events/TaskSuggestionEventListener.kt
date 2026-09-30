// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.events

import io.github.scriptibus.jofi.shared.domain.DomainEvent
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.tasks.application.RequestTaskSuggestionsUseCase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Hands every domain event to [RequestTaskSuggestionsUseCase] once the change that published it is committed (#95),
 * so a change that is rolled back suggests nothing. It only queues a job, in the job store's own connection, so it
 * writes nothing in the committed transaction. A failure is logged (the event type only) and left to the daily run;
 * the user's change stands either way.
 */
class TaskSuggestionEventListener(
    private val request: RequestTaskSuggestionsUseCase,
) {
    @TransactionalEventListener
    fun on(event: DomainEvent) {
        when (val queued = request.execute(event)) {
            null, is JobResult.Success -> Unit
            else -> log.warn("Queuing the task suggestions after {} failed: {}", event.javaClass.simpleName, queued)
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(TaskSuggestionEventListener::class.java)
    }
}
