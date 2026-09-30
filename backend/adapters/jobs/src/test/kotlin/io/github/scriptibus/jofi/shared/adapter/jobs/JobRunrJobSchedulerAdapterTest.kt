// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.github.scriptibus.jofi.shared.domain.job.CronSchedule
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.github.scriptibus.jofi.shared.domain.job.RecurringJobId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.states.EnqueuedState
import org.jobrunr.jobs.states.ProcessingState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.jobs.states.SucceededState
import org.jobrunr.scheduling.JobRequestScheduler
import org.jobrunr.storage.StorageException
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.navigation.AmountRequest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

class JobRunrJobSchedulerAdapterTest {
    private val storage = inMemoryJobStore()
    private val adapter = JobRunrJobSchedulerAdapter(JobRequestScheduler(storage), storage)
    private val berlin = ZoneId.of("Europe/Berlin")
    private val request = JobRequest(JobType("scanner-run"), mapOf("scannerId" to "7"))
    private val id = RecurringJobId("scanner-7")

    private fun jobs(state: StateName) = storage.getJobList(state, AmountRequest("updatedAt:ASC", 100))

    @Test
    fun `an enqueued job is named by its type and carries only the request`() {
        val result = adapter.enqueue(request)

        val job = jobs(StateName.ENQUEUED).single()
        result shouldBe JobResult.Success(JobId(job.id))
        job.jobName shouldBe "scanner-run"
        job.jobDetails.jobParameterValues.single() shouldBe
            JofiJobRequest("scanner-run", linkedMapOf("scannerId" to "7"))
    }

    @Test
    fun `a recurring schedule is stored with its cron expression, zone and random delay`() {
        adapter.scheduleRecurring(id, CronSchedule("0 7 * * *", berlin, Duration.ofMinutes(15)), request) shouldBe
            JobResult.Success(Unit)

        val recurring = storage.recurringJobs.single()
        recurring.id shouldBe "scanner-7"
        recurring.jobName shouldBe "scanner-run"
        recurring.scheduleExpression shouldBe "0 7 * * *"
        recurring.zoneId shouldBe "Europe/Berlin"
        recurring.jobDetails.jobParameterValues.single() shouldBe
            JofiJobRequest("scanner-run", linkedMapOf("scannerId" to "7"), maxRandomDelaySeconds = 900)
    }

    @Test
    fun `registering an unchanged schedule again keeps it and its pending run`() {
        val schedule = CronSchedule("0 7 * * *", berlin)
        adapter.scheduleRecurring(id, schedule, request)
        val pending = storage.save(storage.recurringJobs.single().toScheduledJob())
        val before = storage.recurringJobs.single()

        adapter.scheduleRecurring(id, schedule, request) shouldBe JobResult.Success(Unit)

        storage.recurringJobs.single().createdAt shouldBe before.createdAt
        storage.getJobById(pending.id).state shouldBe StateName.SCHEDULED
    }

    @Test
    fun `a changed schedule replaces the old one and drops its pending run`() {
        adapter.scheduleRecurring(id, CronSchedule("0 7 * * *", berlin), request)
        val pending = storage.save(storage.recurringJobs.single().toScheduledJob())

        adapter.scheduleRecurring(id, CronSchedule("30 6 * * *", berlin), request) shouldBe JobResult.Success(Unit)

        storage.recurringJobs.single().scheduleExpression shouldBe "30 6 * * *"
        storage.getJobById(pending.id).state shouldBe StateName.DELETED
    }

    @Test
    fun `an invalid cron expression is refused`() {
        adapter.scheduleRecurring(id, CronSchedule("61 7 * * *", berlin), request) shouldBe JobResult.InvalidSchedule

        storage.recurringJobs.shouldBeEmpty()
    }

    @Test
    fun `cancelling deletes a job that has not finished`() {
        val job = (adapter.enqueue(request) as JobResult.Success).value

        adapter.cancel(job) shouldBe JobResult.Success(Unit)

        storage.getJobById(job.value).state shouldBe StateName.DELETED
    }

    @Test
    fun `cancelling a finished job changes nothing and an unknown job is not found`() {
        val done =
            storage.save(
                jobWithHistory(
                    "scanner-run",
                    EnqueuedState(),
                    ProcessingState(UUID.randomUUID(), "worker"),
                    SucceededState(Duration.ZERO, Duration.ofSeconds(1)),
                ),
            )

        adapter.cancel(JobId(done.id)) shouldBe JobResult.Success(Unit)
        storage.getJobById(done.id).state shouldBe StateName.SUCCEEDED
        adapter.cancel(JobId(UUID.randomUUID())) shouldBe JobResult.NotFound
    }

    @Test
    fun `cancelling a recurring schedule removes it and its pending runs`() {
        adapter.scheduleRecurring(id, CronSchedule("0 7 * * *", berlin), request)
        val pending = storage.save(storage.recurringJobs.single().toScheduledJob())

        adapter.cancelRecurring(id) shouldBe JobResult.Success(Unit)
        adapter.cancelRecurring(id) shouldBe JobResult.NotFound

        storage.recurringJobs.shouldBeEmpty()
        storage.getJobById(pending.id).state shouldBe StateName.DELETED
    }

    @Test
    fun `a failing job store is a storage failure, never an exception`() {
        val broken = mockk<StorageProvider>(relaxed = true)
        every { broken.recurringJobs } throws StorageException("connection refused to 10.0.0.5")
        every { broken.getJobById(any<UUID>()) } throws StorageException("connection refused")
        every { broken.deleteRecurringJob(any()) } throws StorageException("connection refused")
        every { broken.save(any<Job>()) } throws StorageException("connection refused")
        val failing = JobRunrJobSchedulerAdapter(JobRequestScheduler(broken), broken)

        failing.enqueue(request) shouldBe JobResult.StorageFailure("enqueue")
        failing.scheduleRecurring(id, CronSchedule("0 7 * * *", berlin), request) shouldBe
            JobResult.StorageFailure("schedule-recurring")
        failing.cancel(JobId(UUID.randomUUID())) shouldBe JobResult.StorageFailure("cancel")
        failing.cancelRecurring(id) shouldBe JobResult.StorageFailure("cancel-recurring")
    }
}
