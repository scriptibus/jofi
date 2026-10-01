// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The dashboard's funnel stages, rates and pipeline counts, and the recent activity's scope (ADR-0052). */
class DashboardTest {
    @Test
    fun `each funnel stage contains the next, and a response always follows applying`() {
        FunnelStage.APPLIED.statuses shouldContainAll FunnelStage.INTERVIEW.statuses
        FunnelStage.INTERVIEW.statuses shouldContainAll FunnelStage.OFFER.statuses
        FunnelStage.RESPONSE shouldContainAll FunnelStage.INTERVIEW.statuses
        FunnelStage.APPLIED.statuses shouldContainAll FunnelStage.RESPONSE
    }

    @Test
    fun `the stages are the ones ADR-0052 defines`() {
        FunnelStage.APPLIED.statuses shouldBe
            setOf(
                ApplicationStatus.APPLIED,
                ApplicationStatus.INTERVIEWING,
                ApplicationStatus.OFFER,
                ApplicationStatus.ACCEPTED,
                ApplicationStatus.REJECTED,
                ApplicationStatus.WITHDRAWN,
                ApplicationStatus.GHOSTED,
            )
        FunnelStage.APPLIED.statuses shouldNotContain ApplicationStatus.DECLINED
        FunnelStage.INTERVIEW.statuses shouldBe
            setOf(ApplicationStatus.INTERVIEWING, ApplicationStatus.OFFER, ApplicationStatus.ACCEPTED)
        FunnelStage.OFFER.statuses shouldBe setOf(ApplicationStatus.OFFER, ApplicationStatus.ACCEPTED)
        FunnelStage.RESPONSE shouldBe
            setOf(
                ApplicationStatus.INTERVIEWING,
                ApplicationStatus.OFFER,
                ApplicationStatus.ACCEPTED,
                ApplicationStatus.REJECTED,
            )
    }

    @Test
    fun `rates are shares of their base, absent while the base is zero`() {
        val funnel = ApplicationFunnel(applied = 8, interviewed = 2, offered = 1, responded = 6)

        funnel.interviewRate shouldBe 0.25
        funnel.offerRate shouldBe 0.5
        funnel.responseRate shouldBe 0.75
        ApplicationFunnel.NONE.interviewRate shouldBe null
        ApplicationFunnel.NONE.offerRate shouldBe null
        ApplicationFunnel.NONE.responseRate shouldBe null
        ApplicationFunnel(applied = 3, interviewed = 0, offered = 0, responded = 1).offerRate shouldBe null
    }

    @Test
    fun `a funnel never widens and counts no negatives`() {
        shouldThrow<IllegalArgumentException> { ApplicationFunnel(1, 2, 0, 2) }
        shouldThrow<IllegalArgumentException> { ApplicationFunnel(2, 1, 2, 1) }
        shouldThrow<IllegalArgumentException> { ApplicationFunnel(2, 2, 0, 1) }
        shouldThrow<IllegalArgumentException> { ApplicationFunnel(2, 0, 0, 3) }
        shouldThrow<IllegalArgumentException> { ApplicationFunnel(0, 0, -1, 0) }
    }

    @Test
    fun `the overview lists every status in pipeline order, zero for those without applications`() {
        val overview =
            PipelineOverview.of(
                mapOf(ApplicationStatus.GHOSTED to 2L, ApplicationStatus.APPLIED to 3L),
                1,
                ApplicationFunnel.NONE,
            )

        overview.byStatus.keys.toList() shouldContainExactly ApplicationStatus.entries
        overview.byStatus.getValue(ApplicationStatus.APPLIED) shouldBe 3
        overview.byStatus.getValue(ApplicationStatus.GHOSTED) shouldBe 2
        overview.byStatus.getValue(ApplicationStatus.DISCOVERED) shouldBe 0
        shouldThrow<IllegalArgumentException> { PipelineOverview.of(emptyMap(), 1, ApplicationFunnel.NONE) }
        shouldThrow<IllegalArgumentException> { PipelineOverview(emptyMap(), 0, ApplicationFunnel.NONE) }
    }

    @Test
    fun `an activity query holds 1 to 100 entries`() {
        ActivityQuery().limit shouldBe ActivityQuery.DEFAULT_LIMIT
        ActivityQuery(ActivityQuery.MAX_LIMIT).limit shouldBe 100
        shouldThrow<IllegalArgumentException> { ActivityQuery(0) }
        shouldThrow<IllegalArgumentException> { ActivityQuery(101) }
    }

    @Test
    fun `the activity's entity types of other contexts are their changelog names`() {
        ActivityQuery.ENTITY_TYPES shouldBe
            setOf(
                ApplicationId.ENTITY_TYPE,
                SourceId.ENTITY_TYPE,
                SnapshotId.ENTITY_TYPE,
                InterviewId.ENTITY_TYPE,
                CompanyId.ENTITY_TYPE,
                ContactId.ENTITY_TYPE,
                TaskId.ENTITY_TYPE,
                CountdownId.ENTITY_TYPE,
            )
        ActivityQuery.ENTITY_TYPES shouldNotContain SavedViewId.ENTITY_TYPE
        ActivityQuery.ENTITY_TYPES shouldNotContain ApplicationSettings.ENTITY_TYPE
    }
}
