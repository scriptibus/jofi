// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.jobs

import io.github.scriptibus.jofi.applications.application.RunPostingImportUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PostingImportJobAdapterTest {
    private val useCase = mockk<RunPostingImportUseCase>()
    private val adapter = PostingImportJobAdapter(useCase)
    private val id = ImportId(UUID.randomUUID())
    private val arguments = mapOf("import" to id.value.toString())

    @Test
    fun `it handles the posting import job type and runs the import as the AI`() {
        val failed =
            PostingImport
                .start(id, DescriptionText("Text"), Instant.parse("2026-09-30T12:00:00Z"))
                .failed(ImportFailure.AI_UNAVAILABLE, Instant.parse("2026-09-30T12:00:01Z"))
        every { useCase.execute(id, Actor.Ai) } returns ApplicationResult.Success(failed)

        adapter.type shouldBe JobType("posting-import")
        adapter.run(arguments) shouldBe JobOutcome.Done
        verify { useCase.execute(id, Actor.Ai) }
    }

    @Test
    fun `storage failures are retried, a gone import or broken arguments are given up`() {
        every { useCase.execute(id, Actor.Ai) } returns ApplicationResult.StorageFailure("company")
        adapter.run(arguments) shouldBe JobOutcome.Retry(FailureReason("storage-failure"))

        every { useCase.execute(id, Actor.Ai) } returns ApplicationResult.VersionConflict
        adapter.run(arguments) shouldBe JobOutcome.Done

        every { useCase.execute(id, Actor.Ai) } returns ApplicationResult.ImportNotFound
        adapter.run(arguments) shouldBe JobOutcome.GiveUp(FailureReason("import-gone"))

        every { useCase.execute(id, Actor.Ai) } returns ApplicationResult.NotFound
        adapter.run(arguments) shouldBe JobOutcome.GiveUp(FailureReason("unexpected-result"))

        adapter.run(emptyMap()) shouldBe JobOutcome.GiveUp(FailureReason("invalid-arguments"))
        adapter.run(mapOf("import" to "not-a-uuid")) shouldBe JobOutcome.GiveUp(FailureReason("invalid-arguments"))
    }
}
