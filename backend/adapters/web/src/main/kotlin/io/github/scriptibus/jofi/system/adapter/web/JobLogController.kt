// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.shared.domain.job.JobLogQuery
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import io.github.scriptibus.jofi.system.application.ListJobsUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** The job log (spec §13): background jobs with status, attempts and failure reason, newest first. */
@RestController
@RequestMapping("/api/system/jobs")
class JobLogController(
    private val jobs: ListJobsUseCase,
) {
    @GetMapping
    fun listJobs(
        @RequestParam(required = false) status: JobLogStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${JobLogQuery.DEFAULT_SIZE}") size: Int,
    ): JobLogPageResponse {
        if (!JobLogQuery.isValid(page, size)) {
            throw problem(
                HttpStatus.BAD_REQUEST,
                INVALID_PAGE,
                "page >= 0 and size 1 to ${JobLogQuery.MAX_SIZE}, within the newest ${JobLogQuery.MAX_WINDOW} jobs",
            )
        }
        return when (val result = jobs.execute(JobLogQuery(status?.toDomain(), page, size))) {
            is JobResult.Success -> JobLogPageResponse.from(result.value, page, size)
            else -> throw problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "The job log cannot be read right now")
        }
    }

    private fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ErrorResponseException {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply { this.type = URI.create(type) }
        return ErrorResponseException(status, problem, null)
    }

    companion object {
        const val INVALID_PAGE = "urn:jofi:problem:system:invalid-job-log-page"
        const val UNAVAILABLE = "urn:jofi:problem:system:job-log-unavailable"
    }
}
