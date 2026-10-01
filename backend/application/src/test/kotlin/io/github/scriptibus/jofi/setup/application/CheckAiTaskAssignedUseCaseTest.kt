// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort.Assignment
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

class CheckAiTaskAssignedUseCaseTest {
    private val fixtures = SetupFixtures()

    @Test
    fun `a task is assigned exactly when it has a model`() {
        val check = CheckAiTaskAssignedUseCase(fixtures.assignmentPort)
        fixtures.assignments[AiTask.EXTRACTION] =
            ModelAssignment(AiTask.EXTRACTION, ProviderId(UUID.randomUUID()), ModelName("qwen3"))

        check.execute(AiTask.EXTRACTION) shouldBe Assignment.Assigned
        check.execute(AiTask.CHAT) shouldBe Assignment.NotAssigned
    }

    @Test
    fun `unreadable assignments are unavailable, not unassigned`() {
        val broken = mockk<ModelAssignmentPort>()
        every { broken.findByTask(any()) } returns SetupStoreResult.StorageFailure("assignments")

        CheckAiTaskAssignedUseCase(broken).execute(AiTask.EXTRACTION) shouldBe Assignment.Unavailable
    }
}
