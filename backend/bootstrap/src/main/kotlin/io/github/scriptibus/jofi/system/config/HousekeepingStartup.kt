// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.system.application.ScheduleHousekeepingUseCase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

/**
 * Registers the recurring housekeeping jobs once `app` is up (never in the worker, which only runs
 * jobs, and never in the image's AOT training run, which runs no runners). A failure is logged and
 * retried at the next start; the app itself does not depend on it.
 */
class HousekeepingStartup(
    private val scheduleHousekeeping: ScheduleHousekeepingUseCase,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        when (val result = scheduleHousekeeping.execute()) {
            is JobResult.Success -> logger.info("Housekeeping jobs are scheduled")
            else -> logger.error("Scheduling the housekeeping jobs failed: {}", result)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(HousekeepingStartup::class.java)
    }
}
