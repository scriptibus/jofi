// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.job

import java.time.Instant

/** Where a background job is in its life cycle. */
enum class JobStatus {
    /** Waiting for its start time (a delayed run, a retry with backoff, the next recurring slot). */
    SCHEDULED,

    /** Ready to run; the next free worker picks it up. */
    ENQUEUED,
    PROCESSING,
    SUCCEEDED,

    /** Failed and out of retries (or not retryable). Kept until the user deletes it. */
    FAILED,

    /** Cancelled, or a succeeded job past its retention. */
    DELETED,
}

/**
 * One line of the job log the user sees (spec §13): which job ([name] is its type), where it stands,
 * how often it ran, and why it last failed. Never holds the job's arguments.
 */
data class JobLogEntry(
    val id: JobId,
    val name: String,
    val status: JobStatus,
    /** How often a worker started the job, retries included. */
    val attempts: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** The reason code of the latest failure, if the job ever failed. */
    val lastFailure: FailureReason? = null,
) {
    init {
        require(attempts >= 0) { "Attempts must not be negative" }
    }
}

/** One page of the job log, newest change first. [total] counts all jobs matching the query. */
data class JobLogPage(
    val entries: List<JobLogEntry>,
    val total: Long,
)

/** Which page of the job log to read: optionally one [status] only, [size] entries per [page]. */
data class JobLogQuery(
    val status: JobStatus? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        require(isValid(page, size)) {
            "A job log page holds 1 to $MAX_SIZE entries and ends within the newest $MAX_WINDOW jobs"
        }
    }

    /** How many entries come before this page. */
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        /** Keeps a read bounded: the log across all states is merged from one read per state. */
        const val MAX_WINDOW = 1000

        /** Whether [page] and [size] make a valid query (for callers that turn input into a query). */
        fun isValid(
            page: Int,
            size: Int,
        ): Boolean = page >= 0 && size in 1..MAX_SIZE && (page.toLong() + 1) * size <= MAX_WINDOW
    }
}
