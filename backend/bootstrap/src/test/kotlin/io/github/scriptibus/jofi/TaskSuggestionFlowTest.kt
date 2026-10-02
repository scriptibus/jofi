// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.TASK
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.github.scriptibus.jofi.tasks.adapter.jobs.TaskSuggestionsJobAdapter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The task suggestions of #95 behind the real filter chain and database, on a clock the test moves: applying queues a
 * run, and 14 days later (the default follow-up period) the run suggests a follow-up, which one click opens as the
 * user; logging an interview in Tokyo suggests preparing on the day before on Tokyo's calendar; rescheduling it to
 * another day dismisses that suggestion as its rule and suggests the new day. The worker's part is played by calling
 * the job handler, as the worker would (the worker itself: `BackgroundJobsTest`). No session is 401, no CSRF 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class, TaskSuggestionFlowTest.ClockConfiguration::class)
class TaskSuggestionFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val clock: MovableClock,
    @param:Autowired private val job: TaskSuggestionsJobAdapter,
) {
    private val json = JsonMapper.builder().build()

    /** A clock the test sets. */
    class MovableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    /** Replaces the app's clock with the [MovableClock]. */
    @TestConfiguration
    class ClockConfiguration {
        @Bean
        @Primary
        fun movableClock(): MovableClock = MovableClock(START)
    }

    @BeforeEach
    fun startWithoutUserOrTasks() {
        clock.now = START
        dsl.deleteFrom(TASK).execute()
        dsl.deleteFrom(APPLICATION).execute()
        dsl.deleteFrom(COMPANY).execute()
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
    }

    private fun owner(): Browser =
        Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open().also {
            val body = """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}"""
            it.post("/api/auth/first-run", body).response.status shouldBe 204
        }

    private fun MvcTestResult.ok(status: Int = 200): JsonNode =
        also { response.status shouldBe status }.let { json.readTree(response.contentAsString) }

    private fun Browser.suggestions(): List<JsonNode> =
        get("/api/tasks/suggestions").ok()["tasks"].let { tasks -> (0 until tasks.size()).map { tasks[it] } }

    private fun queuedRuns(): Int =
        (
            dsl.fetchValue(
                "SELECT count(*) FROM jobrunr_jobs WHERE state = 'ENQUEUED' AND jobasjson LIKE '%task-suggestions%'",
            ) as Number
        ).toInt()

    private fun runJob() {
        job.run(emptyMap()) shouldBe JobOutcome.Done
    }

    @Test
    fun `a follow-up after the configured days is accepted, an interview's preparation follows its reschedule`() {
        val browser = owner()
        val company = browser.post("/api/companies", """{"name":"ACME GmbH"}""").ok(201)["id"].asString()
        val application =
            browser
                .post("/api/applications", """{"title":"Backend Engineer","companyId":"$company"}""")
                .ok(201)["id"]
                .asString()
        val queued = queuedRuns()
        browser.put("/api/applications/$application/status", """{"status":"SHORTLISTED","basedOnVersion":0}""").ok()
        browser.put("/api/applications/$application/status", """{"status":"APPLIED","basedOnVersion":1}""").ok()
        queuedRuns() shouldBe queued + 2

        runJob()
        browser.suggestions() shouldBe emptyList()
        clock.now = START.plus(Duration.ofDays(14))
        runJob()
        runJob()

        val followUp = browser.suggestions().single()
        followUp["title"].asString() shouldBe "Follow up: Backend Engineer"
        followUp["suggestionRule"].asString() shouldBe "follow-up"
        followUp["timing"]["startsOn"].asString() shouldBe "2026-10-15"
        val accepted = browser.post("/api/tasks/${followUp["id"].asString()}/accept", """{"basedOnVersion":0}""").ok()
        accepted["status"].asString() shouldBe "OPEN"
        browser.suggestions() shouldBe emptyList()
        actorsOf(followUp["id"].asString()) shouldContainExactly
            listOf("SYSTEM follow-up Suggested task", "USER null Accepted suggestion")

        prepareForInterview(browser, application)
    }

    /** 08:00 on 20 October in Tokyo is 19 October in UTC: the preparation is due on 19 October, Tokyo's day before. */
    private fun prepareForInterview(
        browser: Browser,
        application: String,
    ) {
        val path = "/api/applications/$application/interviews"
        val body = { day: Int -> """{"type":"HR","localStart":"2026-10-${day}T08:00","timeZone":"Asia/Tokyo"}""" }
        val queued = queuedRuns()
        val interview = browser.post(path, body(20)).ok(201)["id"].asString()
        queuedRuns() shouldBe queued + 1
        runJob()
        val first = browser.suggestions().single()
        first["suggestionRule"].asString() shouldBe "interview-preparation"
        first["title"].asString() shouldBe "Prepare for the interview: Backend Engineer"
        first["timing"]["startsOn"].asString() shouldBe "2026-10-19"

        browser.put("$path/$interview", """{"details":${body(22)},"basedOnVersion":0}""").ok()
        queuedRuns() shouldBe queued + 2
        runJob()

        browser.suggestions().single()["timing"]["startsOn"].asString() shouldBe "2026-10-21"
        browser.get("/api/tasks/${first["id"].asString()}").ok()["status"].asString() shouldBe "DISMISSED"
        actorsOf(first["id"].asString()) shouldContainExactly
            listOf(
                "SYSTEM interview-preparation Suggested task",
                "SYSTEM interview-preparation Dismissed obsolete suggestion",
            )
    }

    private fun actorsOf(task: String): List<String> =
        dsl
            .selectFrom(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq("task"))
            .and(CHANGELOG_ENTRY.ENTITY_ID.eq(task))
            .orderBy(CHANGELOG_ENTRY.ID)
            .fetch { "${it.actorKind} ${it.actorName} ${it.description}" }

    @Test
    fun `without a session accepting is 401, without the CSRF token 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        val path = "/api/tasks/${UUID.randomUUID()}/accept"

        anonymous.post(path, """{"basedOnVersion":0}""").response.status shouldBe 401
        anonymous.get("/api/tasks/suggestions").response.status shouldBe 401
        browser.post(path, """{"basedOnVersion":0}""", csrf = null).response.status shouldBe 403
        browser.post(path, """{"basedOnVersion":0}""").response.status shouldBe 404
    }

    @Test
    fun `accepting a task the user created is 409, and the task stays open`() {
        val browser = owner()
        val manual =
            browser
                .post("/api/tasks", """{"title":"Mine","timing":{"timeZone":"UTC","bucket":"TODAY"}}""")
                .ok(201)["id"]
                .asString()

        browser.post("/api/tasks/$manual/accept", """{"basedOnVersion":0}""").ok(409)["type"].asString() shouldContain
            "invalid-transition"
        browser.get("/api/tasks/$manual").ok()["version"].asInt() shouldBe 0
        actorsOf(manual) shouldContainExactly listOf("USER null Created task")
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val START: Instant = Instant.parse("2026-10-01T10:00:00Z")
        val addresses = AtomicInteger(60)
    }
}
