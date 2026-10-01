// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.application.port.DashboardRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ActivityApplication
import io.github.scriptibus.jofi.applications.domain.ActivityEntry
import io.github.scriptibus.jofi.applications.domain.ActivityQuery
import io.github.scriptibus.jofi.applications.domain.ApplicationFunnel
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.PipelineOverview
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

/** The dashboard's pipeline overview and recent activity (#113): reads that hand the store's answer on as a result. */
class DashboardUseCasesTest {
    private val dashboard = mockk<DashboardRepositoryPort>()
    private val pipeline = GetPipelineOverviewUseCase(dashboard)
    private val activity = ListRecentActivityUseCase(dashboard)

    private val overview =
        PipelineOverview.of(mapOf(ApplicationStatus.APPLIED to 2L), 1, ApplicationFunnel(2, 1, 0, 1))

    private val application = ApplicationId(UUID.randomUUID())
    private val entry =
        ActivityEntry(
            id = 7,
            occurredAt = NOW,
            actor = Actor.Scanner("arbeitsagentur"),
            entity = application.toEntityRef(),
            description = "Created application",
            fields = listOf("title"),
            application = ActivityApplication(application, "Backend Engineer"),
        )

    @Test
    fun `the pipeline overview is the store's`() {
        every { dashboard.pipeline() } returns ApplicationStoreResult.Success(overview)

        pipeline.execute() shouldBe ApplicationResult.Success(overview)
    }

    @Test
    fun `the recent activity asks the store for the query's limit`() {
        every { dashboard.recentActivity(ActivityQuery(5)) } returns ApplicationStoreResult.Success(listOf(entry))

        activity.execute(ActivityQuery(5)) shouldBe ApplicationResult.Success(listOf(entry))
        verify(exactly = 1) { dashboard.recentActivity(ActivityQuery(5)) }
    }

    @Test
    fun `an entry of another context carries no application`() {
        val task = entry.copy(entity = EntityRef("task", UUID.randomUUID().toString()), application = null)
        every { dashboard.recentActivity(any()) } returns ApplicationStoreResult.Success(listOf(task))

        activity.execute(ActivityQuery()) shouldBe ApplicationResult.Success(listOf(task))
    }

    @Test
    fun `a storage failure is a result, not an exception`() {
        every { dashboard.pipeline() } returns ApplicationStoreResult.StorageFailure("pipeline")
        every { dashboard.recentActivity(any()) } returns ApplicationStoreResult.StorageFailure("recentActivity")

        pipeline.execute() shouldBe ApplicationResult.StorageFailure("pipeline")
        activity.execute(ActivityQuery()) shouldBe ApplicationResult.StorageFailure("recentActivity")
    }
}
