// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import org.jobrunr.jobs.JobId
import org.jobrunr.jobs.annotations.Recurring
import org.jobrunr.scheduling.BackgroundJob
import org.jobrunr.scheduling.JobScheduler

/** Known-bad, although in the jobs adapter's package: a lambda job through the `JobScheduler` bean. */
class JobSchedulerLambdaFixture(
    private val scheduler: JobScheduler,
) {
    fun enqueue(): JobId = scheduler.enqueue { println("lambda job") }
}

/** Known-bad: the static `BackgroundJob` API. */
class BackgroundJobLambdaFixture {
    fun enqueue(): JobId = BackgroundJob.enqueue { println("lambda job") }
}

/** Known-bad: an annotated recurring job. */
class RecurringAnnotationFixture {
    @Recurring(id = "fixture", cron = "0 7 * * *")
    fun run() = Unit
}
