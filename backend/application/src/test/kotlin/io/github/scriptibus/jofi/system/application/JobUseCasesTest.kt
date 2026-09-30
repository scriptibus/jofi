// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.JobLogPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.job.JobLogPage
import io.github.scriptibus.jofi.shared.domain.job.JobLogQuery
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.JobStatus
import io.github.scriptibus.jofi.system.application.port.ExpiredSessionsPort
import io.github.scriptibus.jofi.system.domain.SessionCleanup
import io.github.scriptibus.jofi.system.domain.SessionCleanupResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ListJobsUseCaseTest {
    @Test
    fun `the job log comes from the job store`() {
        val query = JobLogQuery(JobStatus.FAILED, page = 1, size = 10)
        val page = JobLogPage(emptyList(), total = 3)
        val jobLog = mockk<JobLogPort> { every { list(query) } returns JobResult.Success(page) }

        ListJobsUseCase(jobLog).execute(query) shouldBe JobResult.Success(page)
    }
}

class ScheduleHousekeepingUseCaseTest {
    @Test
    fun `the session cleanup is registered as an hourly recurring job`() {
        val jobs = mockk<JobSchedulerPort>()
        every {
            jobs.scheduleRecurring(SessionCleanup.RECURRING_ID, SessionCleanup.SCHEDULE, SessionCleanup.REQUEST)
        } returns JobResult.Success(Unit)

        ScheduleHousekeepingUseCase(jobs).execute() shouldBe JobResult.Success(Unit)
    }

    @Test
    fun `a job store failure is reported, not thrown`() {
        val jobs = mockk<JobSchedulerPort>()
        every {
            jobs.scheduleRecurring(SessionCleanup.RECURRING_ID, SessionCleanup.SCHEDULE, SessionCleanup.REQUEST)
        } returns JobResult.StorageFailure("schedule-recurring")

        ScheduleHousekeepingUseCase(jobs).execute() shouldBe JobResult.StorageFailure("schedule-recurring")
    }
}

class CleanUpExpiredSessionsUseCaseTest {
    private val changelog = FakeChangelog()
    private var committed: Boolean? = null
    private val transactions =
        object : TransactionPort {
            override fun <T> inTransaction(
                commitIf: (T) -> Boolean,
                work: () -> T,
            ): T = work().also { committed = commitIf(it) }
        }

    private fun useCase(result: SessionCleanupResult): CleanUpExpiredSessionsUseCase {
        val sessions = mockk<ExpiredSessionsPort> { every { deleteExpired(AuthFixtures.NOW) } returns result }
        return CleanUpExpiredSessionsUseCase(sessions, changelog, transactions, AuthFixtures.clock)
    }

    @Test
    fun `deleted sessions are recorded with the job as actor, in the same transaction`() {
        useCase(SessionCleanupResult.Cleaned(3)).execute() shouldBe SessionCleanupResult.Cleaned(3)

        committed shouldBe true
        changelog.entries.single().let {
            it.actor shouldBe Actor.System("session-cleanup")
            it.entity shouldBe EntityRef("login-session", "expired")
            it.occurredAt shouldBe AuthFixtures.NOW
            it.change.description shouldBe "Deleted 3 expired login session(s)"
        }
    }

    @Test
    fun `a run without expired sessions writes no changelog entry`() {
        useCase(SessionCleanupResult.Cleaned(0)).execute() shouldBe SessionCleanupResult.Cleaned(0)

        changelog.entries.shouldBeEmpty()
    }

    @Test
    fun `a failed changelog write rolls the deletion back`() {
        changelog.failing = true

        useCase(SessionCleanupResult.Cleaned(2)).execute() shouldBe SessionCleanupResult.StorageFailure

        committed shouldBe false
    }

    @Test
    fun `a failed deletion is reported and rolled back`() {
        useCase(SessionCleanupResult.StorageFailure).execute() shouldBe SessionCleanupResult.StorageFailure

        committed shouldBe false
        changelog.entries.shouldBeEmpty()
    }
}
