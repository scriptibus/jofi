// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.config

import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.tasks.application.ScheduleGhostedSuggestionUseCase
import io.github.scriptibus.jofi.tasks.application.ScheduleTaskSuggestionsUseCase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

/**
 * Registers the daily Ghosted suggestion (#85) and the daily run of the suggestion rules (#95) once `app` is up (never
 * in the worker, never in the image's AOT training run, which runs no runners), like `HousekeepingStartup`. A failure
 * is logged and retried at the next start.
 */
class TaskSuggestionsStartup(
    private val ghosted: ScheduleGhostedSuggestionUseCase,
    private val rules: ScheduleTaskSuggestionsUseCase,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        report("the Ghosted suggestion", ghosted.execute())
        report("the task suggestions", rules.execute())
    }

    private fun report(
        what: String,
        result: JobResult<Unit>,
    ) {
        when (result) {
            is JobResult.Success -> logger.info("Scheduled {}", what)
            else -> logger.error("Scheduling {} failed: {}", what, result)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(TaskSuggestionsStartup::class.java)
    }
}
