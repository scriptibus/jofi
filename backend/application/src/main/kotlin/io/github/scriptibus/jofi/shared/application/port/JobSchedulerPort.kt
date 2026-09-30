// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.job.CronSchedule
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.RecurringJobId

/**
 * Hands work to the background worker (ADR-0010) with retries and a visible job log. The `app`
 * only enqueues; the `worker` runs the jobs (#17). Implementations never throw.
 */
interface JobSchedulerPort {
    /** Queues [request] to run once, as soon as a worker is free. */
    fun enqueue(request: JobRequest): JobResult<JobId>

    /** Creates or replaces the recurring schedule [id]: [request] runs on every [schedule] slot. */
    fun scheduleRecurring(
        id: RecurringJobId,
        schedule: CronSchedule,
        request: JobRequest,
    ): JobResult<Unit>

    /** Cancels job [id] if it has not finished; [JobResult.NotFound] when it is unknown. */
    fun cancel(id: JobId): JobResult<Unit>

    /** Removes the recurring schedule [id]; [JobResult.NotFound] when it is unknown. */
    fun cancelRecurring(id: RecurringJobId): JobResult<Unit>
}
