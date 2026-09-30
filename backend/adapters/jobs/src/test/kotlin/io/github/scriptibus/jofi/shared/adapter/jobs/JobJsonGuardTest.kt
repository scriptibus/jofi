// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.JobDetails
import org.jobrunr.jobs.context.JobDashboardLogger
import org.jobrunr.jobs.states.EnqueuedState
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.ProcessingState
import org.jobrunr.jobs.states.ScheduledState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.jobs.states.SucceededState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Counters outside the gadgets, so reading them never initialises a gadget class. */
object GadgetCounters {
    val staticInits = AtomicInteger()
    val mapConstructions = AtomicInteger()
}

/** A class whose static initialiser must never run because of the job store. */
object StaticInitGadget {
    init {
        GadgetCounters.staticInits.incrementAndGet()
    }
}

/** A map class JobRunr's mapper must never build (maps have their own deserializers in Jackson). */
class MapGadget : HashMap<String, String>() {
    init {
        GadgetCounters.mapConstructions.incrementAndGet()
    }
}

/** The first layer (raw JSON) and the second layer (Jackson's deserializers) against gadgets. */
class JobJsonGuardTest {
    private val json = JobStore.jsonMapper()
    private val mapper = AllowlistJobMapper(json)
    private val stored =
        mapper.serializeJob(
            Job(JobDetails(JofiJobRequest("scanner-run", linkedMapOf("scannerId" to "7"))), EnqueuedState()),
        )
    private val request = JofiJobRequest::class.java.name

    @BeforeEach
    fun reset() {
        GadgetCounters.staticInits.set(0)
        GadgetCounters.mapConstructions.set(0)
    }

    private fun Job.isRejected() =
        jobDetails.jobParameterValues.single() == JofiJobRequest(JofiJobRequest.REJECTED_TYPE)

    @Test
    fun `a parameter class with a static initialiser is never loaded`() {
        val job = mapper.deserializeJob(stored.replace(request, StaticInitGadget::class.java.name))

        GadgetCounters.staticInits.get() shouldBe 0
        job.isRejected() shouldBe true
        job.state shouldBe StateName.ENQUEUED
    }

    @Test
    fun `a map gadget as type id is never built, in either layer`() {
        val tampered =
            stored.replaceFirst(
                "\"jobDetails\":{",
                "\"metadata\":{\"@class\":\"${MapGadget::class.java.name}\"},\"jobDetails\":{",
            )

        mapper.deserializeJob(tampered).isRejected() shouldBe true
        shouldThrowAny { json.deserialize("{\"a\":\"b\"}", MapGadget::class.java) }

        GadgetCounters.mapConstructions.get() shouldBe 0
    }

    @Test
    fun `an InetAddress parameter is never resolved, in either layer`() {
        val tampered =
            stored
                .replace("\"className\":\"$request\"", "\"className\":\"java.net.InetAddress\"")
                .replace("\"actualClassName\":\"$request\"", "\"actualClassName\":\"java.net.InetAddress\"")

        mapper.deserializeJob(tampered).isRejected() shouldBe true
        val refused = shouldThrowAny { json.deserialize("\"jofi-gadget.invalid\"", InetAddress::class.java) }
        generateSequence(refused) { it.cause }.joinToString { it.message.orEmpty() } shouldContain
            "not allowed in the job store"
    }

    @Test
    fun `a job JobRunr cannot read is quarantined with its id and state, not left to block the queue`() {
        val original = mapper.deserializeJob(stored)
        val unreadable = stored.replaceFirst(Regex("\"createdAt\":\"[^\"]+\""), "\"createdAt\":\"yesterday\"")

        val job = mapper.deserializeJob(unreadable)

        job.id shouldBe original.id
        job.state shouldBe StateName.ENQUEUED
        job.isRejected() shouldBe true
    }

    @Test
    fun `a job with every state and JobRunr's own metadata passes the guard with its history`() {
        val worker = UUID.randomUUID()
        val job =
            jobWithHistory(
                "scanner-run",
                EnqueuedState(),
                ProcessingState(worker, "worker"),
                FailedState("failed", JobRunFailedException(FailureReason("storage-failure"), true)),
                ScheduledState(Instant.now(), "Retry 1 of 10"),
                EnqueuedState(),
                ProcessingState(worker, "worker"),
                SucceededState(Duration.ZERO, Duration.ofSeconds(1)),
            )
        job.metadata["jobRunrDashboardLog-2"] =
            JobDashboardLogger.JobDashboardLogLines().apply {
                add(JobDashboardLogger.JobDashboardLogLine(JobDashboardLogger.Level.INFO, "retrying"))
            }
        val stored = mapper.serializeJob(job)

        JobJsonGuard.problem(requireNotNull(JobJsonGuard.parse(stored))) shouldBe null
        mapper.deserializeJob(stored).jobStates.size shouldBe 7
    }

    @Test
    fun `text that is not a JSON job still fails the read`() {
        shouldThrowAny { mapper.deserializeJob("not json") }
    }

    @Test
    fun `the guard accepts what Jofi writes and names the problem otherwise`() {
        val root = requireNotNull(JobJsonGuard.parse(stored))

        JobJsonGuard.problem(root) shouldBe null
        JobJsonGuard.parse("[1]") shouldBe null
        JobJsonGuard.problem(
            requireNotNull(JobJsonGuard.parse(stored.replace("\"methodName\":\"run\"", "\"methodName\":\"exit\""))),
        ) shouldBe
            "method exit"
    }
}
