// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.github.scriptibus.jofi.shared.application.port.JobHandlerPort
import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import org.jobrunr.JobRunrException
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.jobrunr.scheduling.JobBuilder.aJob
import org.jobrunr.scheduling.JobRequestScheduler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ThreadLocalRandom

/**
 * Runs every job in the worker: finds the [JobHandlerPort] for the job's type and turns its
 * [JobOutcome] into what JobRunr understands. A failure becomes a [JobRunFailedException] that holds
 * only the handler's [FailureReason] code: the job store keeps exception messages and stack traces,
 * so nothing a handler or a library puts in a message (personal data, SQL) may reach it.
 */
@Component
class JofiJobRequestHandler(
    private val handlers: ObjectProvider<JobHandlerPort>,
    private val scheduler: JobRequestScheduler,
    private val clock: Clock,
) : JobRequestHandler<JofiJobRequest> {
    private val byType: Map<String, JobHandlerPort> by lazy {
        val all = handlers.orderedStream().toList()
        val types = all.map { it.type.name }
        check(types.size == types.toSet().size) { "Two job handlers claim the same job type: $types" }
        all.associateBy { it.type.name }
    }

    override fun run(jobRequest: JofiJobRequest) {
        val outcome =
            when {
                jobRequest.type == JofiJobRequest.REJECTED_TYPE -> JobOutcome.GiveUp(REJECTED)
                jobRequest.maxRandomDelaySeconds > 0 -> scheduleAfterRandomDelay(jobRequest)
                else -> runHandler(jobRequest)
            }
        when (outcome) {
            JobOutcome.Done -> Unit
            is JobOutcome.Retry -> throw JobRunFailedException(outcome.reason, retryable = true)
            is JobOutcome.GiveUp -> throw JobRunFailedException(outcome.reason, retryable = false)
        }
    }

    private fun runHandler(jobRequest: JofiJobRequest): JobOutcome {
        val handler = byType[jobRequest.type] ?: return JobOutcome.GiveUp(FailureReason.UNKNOWN_TYPE)
        return try {
            handler.run(jobRequest.arguments.toMap())
        } catch (exception: RuntimeException) {
            // Ports never throw; if one does, its message may hold data: log and store the type only.
            logger.error("Job {} failed unexpectedly: {}", jobRequest.type, exception.javaClass.name)
            JobOutcome.Retry(FailureReason.UNEXPECTED)
        }
    }

    // A recurring run with a random delay (proposal §7) only schedules the actual work, so the
    // source is never hit at the exact cron slot. The work runs as a one-off job of the same type.
    private fun scheduleAfterRandomDelay(jobRequest: JofiJobRequest): JobOutcome {
        val delaySeconds = ThreadLocalRandom.current().nextLong(jobRequest.maxRandomDelaySeconds + 1)
        return try {
            scheduler.create(
                aJob()
                    .withName(jobRequest.type)
                    .scheduleAt(clock.instant().plusSeconds(delaySeconds))
                    .withJobRequest(jobRequest.copy(maxRandomDelaySeconds = 0)),
            )
            JobOutcome.Done
        } catch (exception: RuntimeException) {
            logger.error("Scheduling the delayed run of job {} failed: {}", jobRequest.type, exception.javaClass.name)
            JobOutcome.Retry(FailureReason.UNEXPECTED)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(JofiJobRequestHandler::class.java)
        val REJECTED = FailureReason(JofiJobRequest.REJECTED_TYPE)
    }
}

/**
 * A failed run as JobRunr stores it: the message is the [FailureReason] code and there is no cause.
 * JobRunr retries it with backoff unless [retryable] is false.
 */
class JobRunFailedException(
    reason: FailureReason,
    retryable: Boolean,
) : JobRunrException(reason.code, !retryable)
