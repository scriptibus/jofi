// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.GetPipelineOverviewUseCase
import io.github.scriptibus.jofi.applications.application.ListRecentActivityUseCase
import io.github.scriptibus.jofi.applications.application.port.DashboardRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ActivityApplication
import io.github.scriptibus.jofi.applications.domain.ActivityEntry
import io.github.scriptibus.jofi.applications.domain.ActivityQuery
import io.github.scriptibus.jofi.applications.domain.ApplicationFunnel
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.PipelineOverview
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.util.UUID

/**
 * `GET /api/dashboard/pipeline` and `GET /api/dashboard/activity` (#113, ADR-0052) over the real use cases with a
 * mocked repository: the API shape, absent rates, the limit's range and 503. Security is tested in bootstrap.
 */
@WebMvcTest(DashboardController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(DashboardControllerTest.UseCases::class)
class DashboardControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val repository: DashboardRepositoryPort,
) {
    private val application = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private val entries =
        listOf(
            ActivityEntry(
                8,
                Instant.parse("2026-09-30T08:00:01Z"),
                Actor.Scanner("arbeitsagentur"),
                EntityRef("application", application.toString()),
                "Edited application",
                listOf("title", "seniority"),
                ActivityApplication(ApplicationId(application), "Backend Engineer"),
            ),
            ActivityEntry(
                7,
                Instant.parse("2026-09-30T08:00:00Z"),
                Actor.User,
                EntityRef("task", "00000000-0000-0000-0000-0000000000f1"),
                "Created task",
                emptyList(),
                null,
            ),
        )

    @BeforeEach
    fun failByDefault() {
        clearMocks(repository)
        every { repository.pipeline() } returns ApplicationStoreResult.StorageFailure("pipeline")
        every { repository.recentActivity(any()) } returns ApplicationStoreResult.StorageFailure("recentActivity")
    }

    @Test
    fun `the pipeline lists every status in order with the unread count and the funnel's rates`() {
        val counted = mapOf(ApplicationStatus.APPLIED to 3L, ApplicationStatus.INTERVIEWING to 1L)
        every { repository.pipeline() } returns
            ApplicationStoreResult.Success(PipelineOverview.of(counted, 2, ApplicationFunnel(4, 1, 0, 2)))
        val statuses =
            ApplicationStatus.entries.joinToString(",") {
                """{"status":"${it.name}","count":${counted[it] ?: 0}}"""
            }

        mvc
            .get()
            .uri("/api/dashboard/pipeline")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo(
                """
                {"byStatus":[$statuses],"unread":2,
                 "funnel":{"applied":4,"interviewed":1,"offered":0,"responded":2,
                           "interviewRate":0.25,"offerRate":0.0,"responseRate":0.5}}
                """.trimIndent(),
            )
    }

    @Test
    fun `rates without a base are null`() {
        every { repository.pipeline() } returns
            ApplicationStoreResult.Success(PipelineOverview.of(emptyMap(), 0, ApplicationFunnel.NONE))

        mvc
            .get()
            .uri("/api/dashboard/pipeline")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """{"funnel":{"applied":0,"interviewRate":null,"offerRate":null,"responseRate":null}}""",
            )
    }

    @Test
    fun `the activity shows actor, entity, description, field names and the application`() {
        every { repository.recentActivity(ActivityQuery(2)) } returns ApplicationStoreResult.Success(entries)

        mvc
            .get()
            .uri("/api/dashboard/activity?limit=2")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo(
                """
                {"entries":[
                  {"id":8,"occurredAt":"2026-09-30T08:00:01Z","actor":{"kind":"SCANNER","name":"arbeitsagentur"},
                   "entityType":"application","entityId":"$application","description":"Edited application",
                   "fields":["title","seniority"],"application":{"id":"$application","title":"Backend Engineer"}},
                  {"id":7,"occurredAt":"2026-09-30T08:00:00Z","actor":{"kind":"USER","name":null},
                   "entityType":"task","entityId":"00000000-0000-0000-0000-0000000000f1","description":"Created task",
                   "fields":[],"application":null}]}
                """.trimIndent(),
            )
    }

    @Test
    fun `the activity's limit defaults to 20 and outside 1 to 100 is a 400 naming it`() {
        every { repository.recentActivity(ActivityQuery()) } returns ApplicationStoreResult.Success(emptyList())
        mvc
            .get()
            .uri("/api/dashboard/activity")
            .assertThat()
            .hasStatusOk()
        verify(exactly = 1) { repository.recentActivity(ActivityQuery(20)) }

        listOf(0, 101).forEach { limit ->
            mvc
                .get()
                .uri("/api/dashboard/activity?limit=$limit")
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .isLenientlyEqualTo(
                    """
                    {"type":"${ApplicationProblems.INVALID_ACTIVITY_QUERY}",
                     "violations":[{"field":"limit","problem":"OUT_OF_RANGE"}]}
                    """.trimIndent(),
                )
        }
        verify(exactly = 1) { repository.recentActivity(any()) }
    }

    @Test
    fun `a store that cannot answer is a 503`() {
        mvc
            .get()
            .uri("/api/dashboard/pipeline")
            .assertThat()
            .hasStatus(503)
        mvc
            .get()
            .uri("/api/dashboard/activity")
            .assertThat()
            .hasStatus(503)
    }

    class UseCases {
        @Bean
        fun repository() = mockk<DashboardRepositoryPort>()

        @Bean
        fun pipeline(repository: DashboardRepositoryPort) = GetPipelineOverviewUseCase(repository)

        @Bean
        fun activity(repository: DashboardRepositoryPort) = ListRecentActivityUseCase(repository)
    }
}
