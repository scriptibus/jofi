// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.job.CronSchedule
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.RecurringJobId
import org.jobrunr.jobs.RecurringJob
import org.jobrunr.jobs.states.StateName
import org.jobrunr.scheduling.JobBuilder.aJob
import org.jobrunr.scheduling.JobRequestScheduler
import org.jobrunr.scheduling.RecurringJobBuilder.aRecurringJob
import org.jobrunr.scheduling.cron.CronExpression
import org.jobrunr.scheduling.cron.InvalidCronExpressionException
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.navigation.AmountRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [JobSchedulerPort] on JobRunr (ADR-0010). `app` and `worker` both only write to the job store
 * here; only the worker's background job server runs jobs. Job names are the job type, so logs and
 * the job log never show arguments.
 */
@Component
class JobRunrJobSchedulerAdapter(
    private val scheduler: JobRequestScheduler,
    private val storage: StorageProvider,
) : JobSchedulerPort {
    override fun enqueue(request: JobRequest): JobResult<JobId> =
        guarded("enqueue") {
            val id = scheduler.create(aJob().withName(request.type.name).withJobRequest(JofiJobRequest.of(request)))
            JobResult.Success(JobId(id.asUUID()))
        }

    override fun scheduleRecurring(
        id: RecurringJobId,
        schedule: CronSchedule,
        request: JobRequest,
    ): JobResult<Unit> {
        if (!isValidCron(schedule.expression)) return JobResult.InvalidSchedule
        val wanted = JofiJobRequest.of(request, schedule.maxRandomDelay.toSeconds())
        return guarded("schedule-recurring") {
            val existing = storage.recurringJobs.firstOrNull { it.id == id.value }
            // An unchanged schedule stays as it is: re-creating it at every start would drop the run
            // JobRunr has already scheduled ahead, and with it the one catch-up run after downtime.
            if (existing == null || !existing.isSame(schedule, wanted)) {
                existing?.let { deletePendingRuns(id.value) }
                scheduler.createRecurrently(
                    aRecurringJob()
                        .withId(id.value)
                        .withName(request.type.name)
                        .withCron(schedule.expression.trim())
                        .withZoneId(schedule.zone)
                        .withJobRequest(wanted),
                )
            }
            JobResult.Success(Unit)
        }
    }

    override fun cancel(id: JobId): JobResult<Unit> =
        guarded("cancel") {
            val job =
                try {
                    storage.getJobById(id.value)
                } catch (_: JobNotFoundException) {
                    return@guarded JobResult.NotFound
                }
            if (job.state !in FINISHED) scheduler.delete(id.value, "Cancelled")
            JobResult.Success(Unit)
        }

    override fun cancelRecurring(id: RecurringJobId): JobResult<Unit> =
        guarded("cancel-recurring") {
            if (storage.deleteRecurringJob(id.value) == 0) {
                JobResult.NotFound
            } else {
                deletePendingRuns(id.value)
                JobResult.Success(Unit)
            }
        }

    /** Runs of the recurring job [recurringId] that were scheduled or enqueued but have not started. */
    private fun deletePendingRuns(recurringId: String) {
        PENDING.forEach { state ->
            storage
                .getJobList(state, AmountRequest(ORDER, PENDING_LIMIT))
                .filter { it.recurringJobId.orElse(null) == recurringId }
                .forEach { storage.save(it.delete("Recurring schedule changed or cancelled")) }
        }
    }

    private fun RecurringJob.isSame(
        schedule: CronSchedule,
        wanted: JofiJobRequest,
    ): Boolean =
        scheduleExpression == schedule.expression.trim() &&
            zoneId == schedule.zone.id &&
            jobName == wanted.type &&
            jobDetails.jobParameterValues.singleOrNull() == wanted

    private fun isValidCron(expression: String): Boolean =
        try {
            CronExpression(expression.trim())
            true
        } catch (_: InvalidCronExpressionException) {
            false
        }

    private inline fun <T> guarded(
        operation: String,
        block: () -> JobResult<T>,
    ): JobResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            // Only the operation and the exception type: messages can hold SQL or job data.
            logger.error("Job store operation {} failed: {}", operation, exception.javaClass.name)
            JobResult.StorageFailure(operation)
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(JobRunrJobSchedulerAdapter::class.java)
        val FINISHED = setOf(StateName.SUCCEEDED, StateName.FAILED, StateName.DELETED)
        val PENDING = listOf(StateName.AWAITING, StateName.SCHEDULED, StateName.ENQUEUED)
        const val ORDER = "updatedAt:ASC"

        /** A single user has a handful of pending runs; this only bounds a pathological job store. */
        const val PENDING_LIMIT = 1000
    }
}
