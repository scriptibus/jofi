// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.JobLogPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.system.application.CleanUpExpiredSessionsUseCase
import io.github.scriptibus.jofi.system.application.ListJobsUseCase
import io.github.scriptibus.jofi.system.application.ScheduleHousekeepingUseCase
import io.github.scriptibus.jofi.system.application.port.ExpiredSessionsPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock

/** Wires the job log and the housekeeping jobs of the `system` context. */
@Configuration(proxyBeanMethods = false)
class JobsSystemConfiguration {
    @Bean
    fun listJobsUseCase(jobLog: JobLogPort): ListJobsUseCase = ListJobsUseCase(jobLog)

    @Bean
    fun scheduleHousekeepingUseCase(jobs: JobSchedulerPort): ScheduleHousekeepingUseCase =
        ScheduleHousekeepingUseCase(jobs)

    @Bean
    fun cleanUpExpiredSessionsUseCase(
        sessions: ExpiredSessionsPort,
        changelog: ChangelogPort,
        transactions: TransactionPort,
        clock: Clock,
    ): CleanUpExpiredSessionsUseCase = CleanUpExpiredSessionsUseCase(sessions, changelog, transactions, clock)

    /** Only `app` registers schedules; the worker runs them. */
    @Bean
    @Profile("!worker")
    fun housekeepingStartup(scheduleHousekeeping: ScheduleHousekeepingUseCase): HousekeepingStartup =
        HousekeepingStartup(scheduleHousekeeping)
}
