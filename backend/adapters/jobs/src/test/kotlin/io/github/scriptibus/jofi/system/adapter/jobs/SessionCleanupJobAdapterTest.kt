// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.jobs

import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.system.application.CleanUpExpiredSessionsUseCase
import io.github.scriptibus.jofi.system.domain.SessionCleanup
import io.github.scriptibus.jofi.system.domain.SessionCleanupResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class SessionCleanupJobAdapterTest {
    private val useCase = mockk<CleanUpExpiredSessionsUseCase>()
    private val adapter = SessionCleanupJobAdapter(useCase)

    @Test
    fun `it handles the session cleanup job type`() {
        adapter.type shouldBe SessionCleanup.TYPE
    }

    @Test
    fun `a cleanup run is done, a storage failure is retried`() {
        every { useCase.execute() } returns SessionCleanupResult.Cleaned(2)
        adapter.run(emptyMap()) shouldBe JobOutcome.Done

        every { useCase.execute() } returns SessionCleanupResult.StorageFailure
        adapter.run(emptyMap()) shouldBe JobOutcome.Retry(FailureReason("storage-failure"))
    }
}
