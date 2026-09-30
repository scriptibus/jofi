// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.shared.application.port.JobLogPort
import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobId
import io.github.scriptibus.jofi.shared.domain.job.JobLogEntry
import io.github.scriptibus.jofi.shared.domain.job.JobLogPage
import io.github.scriptibus.jofi.shared.domain.job.JobLogQuery
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.shared.domain.job.JobStatus
import io.github.scriptibus.jofi.system.application.ListJobsUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.util.UUID

// Security is the filter chain's job (bootstrap tests); this slice tests the mapping only.
@WebMvcTest(JobLogController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(JobLogControllerTest.UseCaseConfig::class)
class JobLogControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val jobLog: JobLogPort,
) {
    @TestConfiguration
    class UseCaseConfig {
        @Bean
        fun jobLogPort(): JobLogPort = mockk()

        @Bean
        fun listJobsUseCase(jobLog: JobLogPort): ListJobsUseCase = ListJobsUseCase(jobLog)
    }

    private val at = Instant.parse("2026-09-30T07:00:00Z")
    private val entry =
        JobLogEntry(
            id = JobId(UUID.fromString("00000000-0000-0000-0000-00000000000a")),
            name = "session-cleanup",
            status = JobStatus.FAILED,
            attempts = 3,
            createdAt = at,
            updatedAt = at.plusSeconds(60),
            lastFailure = FailureReason("storage-failure"),
        )

    @BeforeEach
    fun answer() {
        every { jobLog.list(any()) } returns JobResult.Success(JobLogPage(listOf(entry), total = 41))
    }

    @Test
    fun `GET jobs returns a page of the job log`() {
        mvc
            .get()
            .uri("/api/system/jobs?status=FAILED&page=2&size=5")
            .accept(MediaType.APPLICATION_JSON)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo(
                """
                {"entries":[{"id":"00000000-0000-0000-0000-00000000000a","name":"session-cleanup","status":"FAILED",
                  "attempts":3,"createdAt":"2026-09-30T07:00:00Z","updatedAt":"2026-09-30T07:01:00Z",
                  "lastFailure":"storage-failure"}],
                 "page":2,"size":5,"total":41}
                """.trimIndent(),
            )
        verify { jobLog.list(JobLogQuery(JobStatus.FAILED, page = 2, size = 5)) }
    }

    @Test
    fun `without parameters the first page of every state is read`() {
        mvc
            .get()
            .uri("/api/system/jobs")
            .assertThat()
            .hasStatusOk()

        verify { jobLog.list(JobLogQuery(null, page = 0, size = 20)) }
    }

    @Test
    fun `a page beyond the window is a problem`() {
        mvc
            .get()
            .uri("/api/system/jobs?page=50&size=20")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .extractingPath("$.type")
            .isEqualTo(JobLogController.INVALID_PAGE)
    }

    @Test
    fun `an unknown status is a bad request`() {
        mvc
            .get()
            .uri("/api/system/jobs?status=EXPLODED")
            .assertThat()
            .hasStatus(400)
            .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `an unreadable job store is a problem without internals`() {
        every { jobLog.list(any()) } returns JobResult.StorageFailure("list-jobs")

        mvc
            .get()
            .uri("/api/system/jobs")
            .assertThat()
            .hasStatus(503)
            .bodyJson()
            .extractingPath("$.type")
            .isEqualTo(JobLogController.UNAVAILABLE)
    }
}
