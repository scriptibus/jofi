// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.JobDetails
import org.jobrunr.jobs.RecurringJob
import org.jobrunr.jobs.states.EnqueuedState
import org.jobrunr.jobs.states.StateName
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** A class that must never be built from the job store: counts every construction. */
class Gadget(
    val type: String = "",
) {
    init {
        instances.incrementAndGet()
    }

    companion object {
        val instances = AtomicInteger()
    }
}

class JobStoreTest {
    private val json = JobStore.jsonMapper()
    private val mapper = AllowlistJobMapper(json)
    private val request = JofiJobRequest("scanner-run", linkedMapOf("scannerId" to "7"), maxRandomDelaySeconds = 900)

    @BeforeEach
    fun resetGadget() {
        Gadget.instances.set(0)
    }

    private fun storedJob(): String = mapper.serializeJob(Job(JobDetails(request), EnqueuedState()))

    @Test
    fun `a job request round-trips through the job store's JSON`() {
        val stored = storedJob()

        val job = mapper.deserializeJob(stored)

        job.jobDetails.jobParameterValues.single() shouldBe request
        job.jobDetails.className shouldBe JofiJobRequestHandler::class.java.name
        JofiJobRequest.isJofiJob(job.jobDetails) shouldBe true
    }

    @Test
    fun `the request prints argument names only`() {
        request.toString() shouldBe "JofiJobRequest(type=scanner-run, arguments=[scannerId])"
        JofiJobRequest("x", linkedMapOf("password" to "hunter2")).toString() shouldNotContain "hunter2"
    }

    @Test
    fun `a parameter of a class outside the allowlist is never built and the job is quarantined`() {
        val tampered = storedJob().replace(JofiJobRequest::class.java.name, Gadget::class.java.name)
        tampered shouldContain Gadget::class.java.name

        val job = mapper.deserializeJob(tampered)

        Gadget.instances.get() shouldBe 0
        job.jobDetails.jobParameterValues.single() shouldBe JofiJobRequest(JofiJobRequest.REJECTED_TYPE)
        job.jobName shouldBe JofiJobRequest.REJECTED_TYPE
        job.state shouldBe StateName.ENQUEUED
    }

    @Test
    fun `a job that calls another class or a static method is quarantined`() {
        val stored = storedJob()
        val original = mapper.deserializeJob(stored)
        val tampered =
            stored
                .replace(JofiJobRequestHandler::class.java.name, "java.lang.System")
                .replace("\"methodName\":\"run\"", "\"methodName\":\"exit\"")

        val job = mapper.deserializeJob(tampered)

        job.id shouldBe original.id
        job.jobDetails.className shouldBe JofiJobRequestHandler::class.java.name
        job.jobDetails.methodName shouldBe "run"
        job.jobDetails.jobParameterValues.single() shouldBe JofiJobRequest(JofiJobRequest.REJECTED_TYPE)
    }

    @Test
    fun `a tampered recurring job keeps its schedule but runs as rejected`() {
        val recurring =
            RecurringJob("scan", JobDetails(request), "0 7 * * *", ZoneOffset.UTC.id, RecurringJob.CreatedBy.API)
        val stored =
            mapper
                .serializeRecurringJob(
                    recurring,
                ).replace(JofiJobRequest::class.java.name, Gadget::class.java.name)

        val job = mapper.deserializeRecurringJob(stored)

        Gadget.instances.get() shouldBe 0
        job.id shouldBe "scan"
        job.scheduleExpression shouldBe "0 7 * * *"
        job.jobDetails.jobParameterValues.toList() shouldContainExactly
            listOf(JofiJobRequest(JofiJobRequest.REJECTED_TYPE))
    }

    @Test
    fun `only JobRunr, JDK and our request classes are allowed`() {
        JobStore.isAllowed(Job::class.java) shouldBe true
        JobStore.isAllowed(java.time.Instant::class.java) shouldBe true
        JobStore.isAllowed(JofiJobRequest::class.java) shouldBe true
        JobStore.isAllowed(Gadget::class.java) shouldBe false
        JobStore.isAllowed(JobStoreTest::class.java) shouldBe false
    }
}
