// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.config

import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.tasks.application.ScheduleGhostedSuggestionUseCase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

/**
 * Registers the daily Ghosted suggestion once `app` is up (never in the worker, never in the image's AOT training
 * run, which runs no runners), like `HousekeepingStartup`. A failure is logged and retried at the next start.
 */
class GhostedSuggestionStartup(
    private val schedule: ScheduleGhostedSuggestionUseCase,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        when (val result = schedule.execute()) {
            is JobResult.Success -> logger.info("The Ghosted suggestion is scheduled")
            else -> logger.error("Scheduling the Ghosted suggestion failed: {}", result)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(GhostedSuggestionStartup::class.java)
    }
}
