// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.job

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

class JobLogTest {
    @Test
    fun `a job log query defaults to the first page of twenty jobs in every state`() {
        val query = JobLogQuery()

        query.status shouldBe null
        query.page shouldBe 0
        query.size shouldBe 20
        query.offset shouldBe 0
        JobLogQuery(JobStatus.FAILED, page = 3, size = 25).offset shouldBe 75
    }

    @ParameterizedTest
    @CsvSource("-1, 20", "0, 0", "0, 101", "10, 100", "50, 20")
    fun `a job log query stays within the newest thousand jobs`(
        page: Int,
        size: Int,
    ) {
        JobLogQuery.isValid(page, size) shouldBe false
        shouldThrow<IllegalArgumentException> { JobLogQuery(null, page, size) }
    }

    @Test
    fun `the last page of the window is allowed`() {
        JobLogQuery.isValid(9, 100) shouldBe true
        JobLogQuery.isValid(49, 20) shouldBe true
        JobLogQuery(null, 9, 100).offset shouldBe 900
    }

    @Test
    fun `a job log entry never has negative attempts`() {
        val at = Instant.parse("2026-09-30T10:00:00Z")
        val id = JobId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

        JobLogEntry(id, "session-cleanup", JobStatus.SUCCEEDED, 1, at, at).lastFailure shouldBe null
        shouldThrow<IllegalArgumentException> { JobLogEntry(id, "session-cleanup", JobStatus.ENQUEUED, -1, at, at) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", "Storage", "storage failure", "-storage", "storage-", "Deleted 3 sessions of max@example.org"],
    )
    fun `a failure reason is a short slug that cannot carry data`(invalid: String) {
        shouldThrow<IllegalArgumentException> { FailureReason(invalid) }
    }

    @Test
    fun `failure reasons have a length limit and well-known codes`() {
        FailureReason("storage-failure").code shouldBe "storage-failure"
        FailureReason("a".repeat(FailureReason.MAX_LENGTH)).code.length shouldBe 64
        shouldThrow<IllegalArgumentException> { FailureReason("a".repeat(FailureReason.MAX_LENGTH + 1)) }
        FailureReason.UNEXPECTED.code shouldBe "unexpected-error"
        FailureReason.UNKNOWN_TYPE.code shouldBe "unknown-job-type"
    }

    @Test
    fun `a job outcome says whether to retry`() {
        val reason = FailureReason("provider-unavailable")
        val outcomes = listOf(JobOutcome.Done, JobOutcome.Retry(reason), JobOutcome.GiveUp(reason))

        val retried =
            outcomes.map {
                when (it) {
                    JobOutcome.Done -> "done"
                    is JobOutcome.Retry -> "retry ${it.reason.code}"
                    is JobOutcome.GiveUp -> "give up ${it.reason.code}"
                }
            }

        retried shouldBe listOf("done", "retry provider-unavailable", "give up provider-unavailable")
    }
}
