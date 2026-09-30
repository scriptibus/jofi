// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.shared.domain.job.JobLogEntry
import io.github.scriptibus.jofi.shared.domain.job.JobLogPage
import io.github.scriptibus.jofi.shared.domain.job.JobStatus
import java.time.Instant
import java.util.UUID

/** Where a background job stands (the API's copy of the domain's job status). */
enum class JobLogStatus {
    SCHEDULED,
    ENQUEUED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    DELETED,
    ;

    fun toDomain(): JobStatus = JobStatus.valueOf(name)

    companion object {
        fun from(status: JobStatus): JobLogStatus = valueOf(status.name)
    }
}

/**
 * One job of the job log. [lastFailure] is a reason code such as `storage-failure`, never an
 * exception message; the job's arguments are not part of the API.
 */
data class JobLogEntryResponse(
    val id: UUID,
    val name: String,
    val status: JobLogStatus,
    val attempts: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastFailure: String?,
) {
    companion object {
        fun from(entry: JobLogEntry): JobLogEntryResponse =
            JobLogEntryResponse(
                id = entry.id.value,
                name = entry.name,
                status = JobLogStatus.from(entry.status),
                attempts = entry.attempts,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
                lastFailure = entry.lastFailure?.code,
            )
    }
}

/** JSON body of `GET /api/system/jobs`: one page, most recently changed job first. */
data class JobLogPageResponse(
    val entries: List<JobLogEntryResponse>,
    val page: Int,
    val size: Int,
    /** All jobs matching the status filter, across pages. */
    val total: Long,
) {
    companion object {
        fun from(
            page: JobLogPage,
            number: Int,
            size: Int,
        ): JobLogPageResponse =
            JobLogPageResponse(page.entries.map(JobLogEntryResponse::from), number, size, page.total)
    }
}
