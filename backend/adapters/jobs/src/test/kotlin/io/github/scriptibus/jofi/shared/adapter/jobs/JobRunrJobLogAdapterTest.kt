// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobLogPage
import io.github.scriptibus.jofi.shared.domain.job.JobLogQuery
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.JobStatus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jobrunr.jobs.states.EnqueuedState
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.ProcessingState
import org.jobrunr.jobs.states.ScheduledState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.jobs.states.SucceededState
import org.jobrunr.storage.StorageException
import org.jobrunr.storage.StorageProvider
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class JobRunrJobLogAdapterTest {
    private val storage = inMemoryJobStore()
    private val adapter = JobRunrJobLogAdapter(storage)
    private val start = Instant.parse("2026-09-30T07:00:00Z")
    private val worker = UUID.randomUUID()

    private fun at(minutes: Long) = start.plusSeconds(minutes * 60)

    private fun processing(minutes: Long) = ProcessingState(worker, "worker", at(minutes), at(minutes))

    private fun failed(
        exceptionType: String,
        message: String,
        minutes: Long,
    ) = FailedState("failed", exceptionType, message, null, null, "stack trace", false, at(minutes))

    private fun page(query: JobLogQuery) = (adapter.list(query) as JobResult.Success<JobLogPage>).value

    @Test
    fun `a job that failed, was retried and succeeded shows its attempts and last failure code`() {
        val job =
            storage.save(
                jobWithHistory(
                    "session-cleanup",
                    EnqueuedState(at(0)),
                    processing(1),
                    failed(JobRunFailedException::class.java.name, "storage-failure", 2),
                    ScheduledState(at(3), "Retry 1 of 10", at(2)),
                    EnqueuedState(at(3)),
                    processing(4),
                    SucceededState(Duration.ZERO, Duration.ofSeconds(1), at(5)),
                ),
            )

        val entry = page(JobLogQuery()).entries.single()

        entry.id shouldBe JobId(job.id)
        entry.name shouldBe "session-cleanup"
        entry.status shouldBe JobStatus.SUCCEEDED
        entry.attempts shouldBe 2
        entry.createdAt shouldBe at(0)
        entry.updatedAt shouldBe at(5)
        entry.lastFailure shouldBe FailureReason("storage-failure")
    }

    @Test
    fun `a foreign exception is reported as unexpected, never with its message`() {
        storage.save(
            jobWithHistory(
                "scanner-run",
                EnqueuedState(at(0)),
                processing(1),
                failed("java.lang.IllegalStateException", "max@example.org not found", 2),
            ),
        )
        storage.save(
            jobWithHistory(
                "scanner-run",
                EnqueuedState(at(0)),
                processing(1),
                failed(JobRunFailedException::class.java.name, "Not A Code: max@example.org", 3),
            ),
        )

        val failures = page(JobLogQuery(JobStatus.FAILED)).entries.map { it.lastFailure }

        failures shouldBe listOf(FailureReason.UNEXPECTED, FailureReason.UNEXPECTED)
    }

    @Test
    fun `the log merges all states newest first and pages through them`() {
        storage.save(jobWithHistory("a", EnqueuedState(at(1))))
        storage.save(jobWithHistory("b", EnqueuedState(at(2)), processing(4)))
        storage.save(jobWithHistory("c", ScheduledState(at(60), "later", at(3))))
        storage.save(
            jobWithHistory(
                "d",
                EnqueuedState(at(0)),
                processing(1),
                SucceededState(Duration.ZERO, Duration.ZERO, at(5)),
            ),
        )

        page(JobLogQuery()).let { all ->
            all.entries.map { it.name } shouldBe listOf("d", "b", "c", "a")
            all.total shouldBe 4
        }
        page(JobLogQuery(page = 1, size = 3)).entries.map { it.name } shouldBe listOf("a")
        page(JobLogQuery(JobStatus.ENQUEUED)).let { enqueued ->
            enqueued.entries.map { it.name } shouldBe listOf("a")
            enqueued.total shouldBe 1
        }
        page(JobLogQuery(JobStatus.SCHEDULED)).entries.map { it.status } shouldBe listOf(JobStatus.SCHEDULED)
    }

    @Test
    fun `every JobRunr state has a job status`() {
        StateName.entries.map(JobLogMapping::statusOf).toSet() shouldBe JobStatus.entries.toSet()
        JobStatus.entries.flatMap(JobLogMapping::statesOf).toSet() shouldBe StateName.entries.toSet()
        JobLogMapping.statesOf(null) shouldBe StateName.entries
    }

    @Test
    fun `a failing job store is a storage failure`() {
        val broken = mockk<StorageProvider>()
        every { broken.getJobList(any(), any()) } throws StorageException("SELECT jobAsJson FROM jobrunr_jobs")

        JobRunrJobLogAdapter(broken).list(JobLogQuery()) shouldBe JobResult.StorageFailure("list-jobs")
    }
}
