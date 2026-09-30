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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The application timeline behind the real filter chain and database (#87): create an application, record its job
 * description, move it through the pipeline (applying freezes the description), link a task and edit it; the
 * timeline shows all of it newest first, pages by cursor, and carries neither the description's text nor changed
 * values. No session is 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class ApplicationTimelineFlowTest(
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
    fun `create, describe, move, link a task and edit, then the timeline shows all of it newest first`() {
        val browser = owner()
        val id = applicationWithHistory(browser)

        val timeline = browser.get("/api/applications/$id/timeline").ok()

        val entries = timeline["entries"].toList()
        entries.map { it["kind"].asString() } shouldContainExactly
            listOf(
                "CHANGE",
                "TASK",
                "STATUS_CHANGE",
                "STATUS_CHANGE",
                "DESCRIPTION_SNAPSHOT",
                "STATUS_CHANGE",
                "CHANGE",
            )
        entries.first()["change"]["fields"].toString() shouldBe """[{"field":"title","before":null,"after":null}]"""
        entries.first()["change"]["actor"]["kind"].asString() shouldBe "USER"
        entries[1]["task"]["title"].asString() shouldBe "Send the portfolio"
        entries.filterNot { it["statusChange"].isNull }.map { it["statusChange"]["to"].asString() } shouldContainExactly
            listOf("APPLIED", "SHORTLISTED", "DISCOVERED")
        entries[4]["descriptionSnapshot"]["frozenAt"].isNull shouldBe false
        timeline["nextCursor"].isNull shouldBe true
        timeline.toString().let {
            it shouldNotContain "Kotlin in Berlin"
            it shouldNotContain "Senior Backend Engineer"
        }
        pagesOfThree(browser, id) shouldContainExactly entries.map { it["kind"].asString() + it["id"].asString() }
    }

    /**
     * Creates an application, records its job description, shortlists it, applies (which freezes the description),
     * links a task and renames it; returns its id.
     */
    private fun applicationWithHistory(browser: Browser): String {
        val company = browser.post("/api/companies", """{"name":"ACME GmbH"}""").ok(201)["id"].asString()
        val details = """{"title":"Backend Engineer","companyId":"$company"}"""
        val id = browser.post("/api/applications", details).ok(201)["id"].asString()
        val source = source(id)
        browser.post("/api/applications/$id/sources/$source/snapshots", """{"description":"Kotlin in Berlin"}""").ok()
        browser.put("/api/applications/$id/status", """{"status":"SHORTLISTED","basedOnVersion":0}""").ok()
        browser.put("/api/applications/$id/status", """{"status":"APPLIED","basedOnVersion":1}""").ok()
        val task =
            """{"title":"Send the portfolio","timing":{"timeZone":"UTC","bucket":"SOMEDAY"},
               "link":{"type":"APPLICATION","id":"$id"}}"""
        browser.post("/api/tasks", task).ok(201)
        val renamed = """{"title":"Senior Backend Engineer","companyId":"$company"}"""
        browser.put("/api/applications/$id", """{"details":$renamed,"basedOnVersion":2}""").ok()
        return id
    }

    /** Follows `nextCursor` with pages of three and returns what it saw. */
    private fun pagesOfThree(
        browser: Browser,
        id: String,
    ): List<String> {
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val query = cursor?.let { "&cursor=$it" }.orEmpty()
            val page = browser.get("/api/applications/$id/timeline?limit=3$query").ok()
            seen += page["entries"].toList().map { it["kind"].asString() + it["id"].asString() }
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        return seen
    }

    @Test
    fun `without a session the timeline is 401, an unknown application 404, a foreign cursor 400`() {
        val browser = owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        val someId = UUID.randomUUID()

        anonymous.get("/api/applications/$someId/timeline").response.status shouldBe 401
        browser.get("/api/applications/$someId/timeline").response.status shouldBe 404
        browser.get("/api/applications/$someId/timeline?cursor=nope").response.status shouldBe 400
    }

    /** A source with no API yet (#96): seeded as a row, as the e2e seeds do for data without an API. */
    private fun source(application: String): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            "insert into application_source (id, application_id, kind, discovered_at) " +
                "values (?, ?, 'MANUAL_CHAT', now())",
            id,
            UUID.fromString(application),
        )
        return id
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger(150)
    }
}
