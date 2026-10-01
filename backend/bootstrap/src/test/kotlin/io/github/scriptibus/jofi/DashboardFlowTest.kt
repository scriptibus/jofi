// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.TASK
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * The dashboard behind the real filter chain and database (#113, ADR-0052): create applications, move one to an
 * interview, mark one unread and add tasks; the pipeline, the activity, the tasks and the AI cost of the month answer
 * from what was done, and without a session none of them answers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class DashboardFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun startWithoutUserOrApplications() {
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
        also { response.status shouldBe status }.let { json.readTree(it.response.contentAsString) }

    @Test
    fun `the pipeline counts statuses, unread and the funnel from what the user did`() {
        val browser = owner()
        val interviewing = applicationsWithOneInterview(browser)

        val pipeline = browser.get("/api/dashboard/pipeline").ok()

        val counts = pipeline["byStatus"].toList().associate { it["status"].asString() to it["count"].asLong() }
        counts.filterValues { it > 0 } shouldBe mapOf("DISCOVERED" to 1L, "INTERVIEWING" to 1L)
        pipeline["unread"].asLong() shouldBe 1
        val funnel = pipeline["funnel"]
        listOf("applied", "interviewed", "offered", "responded").map { funnel[it].asLong() } shouldContainExactly
            listOf(1L, 1L, 0L, 1L)
        funnel["responseRate"].asDouble() shouldBe 1.0
        funnel["offerRate"].asDouble() shouldBe 0.0

        val activity = browser.get("/api/dashboard/activity?limit=3").ok()["entries"].toList()
        activity.map { it["description"].asString() } shouldContainExactly
            listOf("Marked application unread", "Changed application status", "Changed application status")
        activity.forEach {
            it["actor"]["kind"].asString() shouldBe "USER"
            it["application"]["title"].asString() shouldBe "Backend Engineer"
        }
        activity[1]["entityId"].asString() shouldBe interviewing
        activity[1]["fields"].toList().map { it.asString() } shouldContainExactly listOf("status")
    }

    /** A discovered application marked unread and one moved through applied to interviewing; returns the latter. */
    private fun applicationsWithOneInterview(browser: Browser): String {
        val company = browser.post("/api/companies", """{"name":"ACME GmbH"}""").ok(201)["id"].asString()
        val details = """{"title":"Backend Engineer","companyId":"$company"}"""
        val waiting = browser.post("/api/applications", details).ok(201)["id"].asString()
        val interviewing = browser.post("/api/applications", details).ok(201)["id"].asString()
        browser.put("/api/applications/$interviewing/status", """{"status":"APPLIED","basedOnVersion":0}""").ok()
        browser.put("/api/applications/$interviewing/status", """{"status":"INTERVIEWING","basedOnVersion":1}""").ok()
        browser.put("/api/applications/$waiting/unread", """{"unread":true}""").ok()
        return interviewing
    }

    @Test
    fun `the tasks are overdue or upcoming on the viewer's calendar, and the month's AI cost has its budget`() {
        val browser = owner()
        val berlin = ZoneId.of("Europe/Berlin")
        val yesterday = LocalDate.now(berlin).minusDays(1)
        task(browser, "Call back", """{"timeZone":"Europe/Berlin","localDue":"${yesterday}T09:00"}""")
        task(browser, "Send the portfolio", """{"timeZone":"Europe/Berlin","bucket":"TODAY"}""")
        task(browser, "Read the book", """{"timeZone":"Europe/Berlin","bucket":"SOMEDAY"}""")

        val tasks = browser.get("/api/dashboard/tasks?timeZone=Europe/Berlin").ok()

        tasks["overdue"].toList().map { it["title"].asString() } shouldContainExactly listOf("Call back")
        tasks["upcoming"].toList().map { it["title"].asString() } shouldContainExactly listOf("Send the portfolio")
        browser.get("/api/dashboard/tasks?timeZone=Mars/Olympus").response.status shouldBe 400

        val activity = browser.get("/api/dashboard/activity?limit=3").ok().toString()
        activity shouldNotContain "Call back"
        activity shouldNotContain "portfolio"

        val costs = browser.get("/api/setup/costs").ok()
        costs["month"].asString() shouldBe YearMonth.now(ZoneOffset.UTC).toString()
        costs["budget"]["month"].asString() shouldBe costs["month"].asString()
    }

    private fun task(
        browser: Browser,
        title: String,
        timing: String,
    ) {
        browser.post("/api/tasks", """{"title":"$title","timing":$timing}""").ok(201)
    }

    @Test
    fun `without a session no dashboard figure answers`() {
        owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()

        listOf(
            "/api/dashboard/pipeline",
            "/api/dashboard/activity",
            "/api/dashboard/tasks?timeZone=UTC",
            "/api/setup/costs",
        ).forEach { anonymous.get(it).response.status shouldBe 401 }
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger(245)
    }
}
