// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.jobs

import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.tasks.application.SuggestGhostedApplicationsUseCase
import io.github.scriptibus.jofi.tasks.domain.GhostedSuggestion
import io.github.scriptibus.jofi.tasks.domain.GhostedSuggestionRun
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class GhostedSuggestionJobAdapterTest {
    private val useCase = mockk<SuggestGhostedApplicationsUseCase>()
    private val adapter = GhostedSuggestionJobAdapter(useCase)

    @Test
    fun `it handles the ghosted suggestion job type`() {
        adapter.type shouldBe GhostedSuggestion.TYPE
    }

    @Test
    fun `a run is done, a storage failure or a suggestion changed meanwhile is retried`() {
        every { useCase.execute() } returns TaskResult.Success(GhostedSuggestionRun(1, 0))
        adapter.run(emptyMap()) shouldBe JobOutcome.Done

        every { useCase.execute() } returns TaskResult.StorageFailure("add")
        adapter.run(emptyMap()) shouldBe JobOutcome.Retry(FailureReason("storage-failure"))

        every { useCase.execute() } returns TaskResult.VersionConflict
        adapter.run(emptyMap()) shouldBe JobOutcome.Retry(FailureReason("suggestion-changed"))
    }
}
