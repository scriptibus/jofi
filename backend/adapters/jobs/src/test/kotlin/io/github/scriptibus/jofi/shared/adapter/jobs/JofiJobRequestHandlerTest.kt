// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.github.scriptibus.jofi.shared.application.port.JobHandlerPort
import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.jobrunr.jobs.states.ScheduledState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.scheduling.JobRequestScheduler
import org.jobrunr.storage.navigation.AmountRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.stream.Stream

class JofiJobRequestHandlerTest {
    private val now = Instant.parse("2026-09-30T07:00:00Z")
    private val storage = inMemoryJobStore()
    private val received = mutableListOf<Map<String, String>>()

    private fun handler(
        type: String,
        outcome: () -> JobOutcome,
    ) = object : JobHandlerPort {
        override val type = JobType(type)

        override fun run(arguments: Map<String, String>): JobOutcome {
            received += arguments
            return outcome()
        }
    }

    private fun dispatcher(vararg handlers: JobHandlerPort): JofiJobRequestHandler {
        val provider =
            mockk<ObjectProvider<JobHandlerPort>> {
                every { orderedStream() } answers
                    { Stream.of(*handlers) }
            }
        return JofiJobRequestHandler(provider, JobRequestScheduler(storage), Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `a job runs the handler of its type with its arguments`() {
        val jobs = dispatcher(handler("scanner-run") { JobOutcome.Done }, handler("other") { error("not me") })

        jobs.run(JofiJobRequest("scanner-run", linkedMapOf("scannerId" to "7")))

        received shouldBe listOf(mapOf("scannerId" to "7"))
    }

    @Test
    fun `a retryable failure is retried and carries only the reason code`() {
        val jobs = dispatcher(handler("scanner-run") { JobOutcome.Retry(FailureReason("source-unavailable")) })

        val failure = shouldThrow<JobRunFailedException> { jobs.run(JofiJobRequest("scanner-run")) }

        failure.message shouldBe "source-unavailable"
        failure.isProblematicAndDoNotRetry shouldBe false
        failure.cause shouldBe null
    }

    @Test
    fun `a permanent failure is not retried`() {
        val jobs = dispatcher(handler("scanner-run") { JobOutcome.GiveUp(FailureReason("scanner-deleted")) })

        val failure = shouldThrow<JobRunFailedException> { jobs.run(JofiJobRequest("scanner-run")) }

        failure.message shouldBe "scanner-deleted"
        failure.isProblematicAndDoNotRetry shouldBe true
    }

    @Test
    fun `an exception is retried without its message, which may hold personal data`() {
        val jobs = dispatcher(handler("scanner-run") { throw IllegalStateException("max@example.org is invalid") })

        val failure = shouldThrow<JobRunFailedException> { jobs.run(JofiJobRequest("scanner-run")) }

        failure.message shouldBe "unexpected-error"
        failure.isProblematicAndDoNotRetry shouldBe false
        failure.stackTraceToString() shouldNotContain "max@example.org"
    }

    @Test
    fun `a checked exception is redacted too`() {
        val jobs =
            dispatcher(handler("scanner-run") { throw java.io.IOException("cannot read /home/max.mustermann/cv.pdf") })

        val failure = shouldThrow<JobRunFailedException> { jobs.run(JofiJobRequest("scanner-run")) }

        failure.message shouldBe "unexpected-error"
        failure.cause shouldBe null
        failure.stackTraceToString() shouldNotContain "max.mustermann"
    }

    @Test
    fun `a job without a handler and a quarantined job fail for good`() {
        val jobs = dispatcher()

        shouldThrow<JobRunFailedException> { jobs.run(JofiJobRequest("nobody-handles-this")) }.let {
            it.message shouldBe "unknown-job-type"
            it.isProblematicAndDoNotRetry shouldBe true
        }
        shouldThrow<JobRunFailedException> { jobs.run(JofiJobRequest(JofiJobRequest.REJECTED_TYPE)) }.let {
            it.message shouldBe "rejected-job"
            it.isProblematicAndDoNotRetry shouldBe true
        }
    }

    @Test
    fun `a run with a random delay schedules the work within the delay instead of doing it`() {
        val jobs = dispatcher(handler("scanner-run") { JobOutcome.Done })

        jobs.run(JofiJobRequest("scanner-run", linkedMapOf("scannerId" to "7"), maxRandomDelaySeconds = 900))

        received.shouldBeEmpty()
        val scheduled = storage.getJobList(StateName.SCHEDULED, AmountRequest("updatedAt:ASC", 10)).single()
        scheduled.jobName shouldBe "scanner-run"
        scheduled.jobDetails.jobParameterValues.single() shouldBe
            JofiJobRequest("scanner-run", linkedMapOf("scannerId" to "7"))
        val at = scheduled.getJobState<ScheduledState>().scheduledAt
        (at >= now && at <= now.plusSeconds(900)) shouldBe true
    }

    @Test
    fun `two handlers for one type are a wiring error`() {
        val jobs = dispatcher(handler("scanner-run") { JobOutcome.Done }, handler("scanner-run") { JobOutcome.Done })

        shouldThrow<IllegalStateException> { jobs.run(JofiJobRequest("scanner-run")) }
    }
}
