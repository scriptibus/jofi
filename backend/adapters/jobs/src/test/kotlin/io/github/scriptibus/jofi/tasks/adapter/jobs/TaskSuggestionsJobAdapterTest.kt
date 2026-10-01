// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.jobs

import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.tasks.application.SuggestTasksUseCase
import io.github.scriptibus.jofi.tasks.domain.SuggestionRun
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskSuggestionRules
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class TaskSuggestionsJobAdapterTest {
    private val useCase = mockk<SuggestTasksUseCase>()
    private val adapter = TaskSuggestionsJobAdapter(useCase)

    @Test
    fun `it handles the task suggestions job type`() {
        adapter.type shouldBe TaskSuggestionRules.TYPE
    }

    @Test
    fun `a run is done, a storage failure or a suggestion changed meanwhile is retried`() {
        every { useCase.execute() } returns TaskResult.Success(SuggestionRun(2, 1))
        adapter.run(emptyMap()) shouldBe JobOutcome.Done

        every { useCase.execute() } returns TaskResult.StorageFailure("find suggestion facts")
        adapter.run(emptyMap()) shouldBe JobOutcome.Retry(FailureReason("storage-failure"))

        every { useCase.execute() } returns TaskResult.VersionConflict
        adapter.run(emptyMap()) shouldBe JobOutcome.Retry(FailureReason("suggestion-changed"))
    }
}
