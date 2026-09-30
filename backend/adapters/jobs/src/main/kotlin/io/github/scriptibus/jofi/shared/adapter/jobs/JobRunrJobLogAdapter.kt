// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.github.scriptibus.jofi.shared.application.port.JobLogPort
import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobLogEntry
import io.github.scriptibus.jofi.shared.domain.job.JobLogPage
import io.github.scriptibus.jofi.shared.domain.job.JobLogQuery
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.JobStatus
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.ProcessingState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.navigation.OffsetBasedPageRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [JobLogPort] on JobRunr's job store. JobRunr pages per state, so the log across all states reads
 * the first `offset + size` jobs of each state and merges them; [JobLogQuery] bounds that window.
 */
@Component
class JobRunrJobLogAdapter(
    private val storage: StorageProvider,
) : JobLogPort {
    override fun list(query: JobLogQuery): JobResult<JobLogPage> =
        try {
            val states = JobLogMapping.statesOf(query.status)
            val window = query.offset + query.size
            val newestFirst =
                states
                    .flatMap { storage.getJobList(it, OffsetBasedPageRequest(ORDER, 0, window.toInt())) }
                    .sortedWith(compareByDescending<Job> { it.updatedAt }.thenBy { it.id })
            val entries = newestFirst.drop(query.offset.toInt()).take(query.size).map(JobLogMapping::toEntry)
            JobResult.Success(JobLogPage(entries, states.sumOf { storage.countJobs(it) }))
        } catch (exception: RuntimeException) {
            logger.error("Reading the job log failed: {}", exception.javaClass.name)
            JobResult.StorageFailure("list-jobs")
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(JobRunrJobLogAdapter::class.java)
        const val ORDER = "updatedAt:DESC"
    }
}

/** Maps JobRunr jobs to job log entries: no arguments, no exception messages, no stack traces. */
internal object JobLogMapping {
    fun statesOf(status: JobStatus?): List<StateName> =
        when (status) {
            null -> StateName.entries

            // Carbon-aware waiting is a kind of scheduling; Jofi never asks for it.
            JobStatus.SCHEDULED -> listOf(StateName.AWAITING, StateName.SCHEDULED)

            else -> listOf(StateName.valueOf(status.name))
        }

    fun statusOf(state: StateName): JobStatus =
        when (state) {
            StateName.AWAITING, StateName.SCHEDULED -> JobStatus.SCHEDULED
            StateName.ENQUEUED -> JobStatus.ENQUEUED
            StateName.PROCESSING -> JobStatus.PROCESSING
            StateName.SUCCEEDED -> JobStatus.SUCCEEDED
            StateName.FAILED -> JobStatus.FAILED
            StateName.DELETED -> JobStatus.DELETED
        }

    fun toEntry(job: Job): JobLogEntry =
        JobLogEntry(
            id = JobId(job.id),
            name = job.jobName,
            status = statusOf(job.state),
            attempts = job.jobStates.count { it is ProcessingState },
            createdAt = job.createdAt,
            updatedAt = job.updatedAt,
            lastFailure = job.getLastJobStateOfType(FailedState::class.java).map(::reasonOf).orElse(null),
        )

    /**
     * Our own failures carry a reason code as their message. Any other exception (a JobRunr error,
     * or a job that could not be read) is reported as unexpected: its message is never shown.
     */
    fun reasonOf(failure: FailedState): FailureReason {
        val code = failure.exceptionMessage
        val ours = failure.exceptionType == JobRunFailedException::class.java.name
        return if (ours && code != null && isReasonCode(code)) FailureReason(code) else FailureReason.UNEXPECTED
    }

    private fun isReasonCode(code: String): Boolean = code.length <= FailureReason.MAX_LENGTH && REASON.matches(code)

    private val REASON = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
}
