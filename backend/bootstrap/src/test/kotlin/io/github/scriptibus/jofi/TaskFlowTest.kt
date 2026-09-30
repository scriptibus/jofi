// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.TASK
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The task API behind the real filter chain and database (#93, #94): create with a bucket resolved in the user's zone,
 * complete, reopen, edit to an exact time and the two-step delete, each recorded with the user as actor and without
 * the title; the open tasks grouped in the viewer's zone; a link to nothing is a 400 found by its foreign key through
 * Spring's exception translation; no session is 401, no CSRF token 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class TaskFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun startWithoutUserOrTasks() {
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

    private fun MvcTestResult.body(): JsonNode = json.readTree(response.contentAsString)

    private fun MvcTestResult.ok(status: Int = 200): JsonNode = also { response.status shouldBe status }.body()

    @Test
    fun `create this week, complete, reopen, edit to an exact time and delete with confirmation`() {
        val browser = owner()
        val company = browser.post("/api/companies", """{"name":"ACME GmbH"}""").ok(201)["id"].asString()
        val request =
            """
            {"title":"Call Erika back","timing":{"timeZone":"Europe/Berlin","bucket":"THIS_WEEK"},
             "link":{"type":"COMPANY","id":"$company"}}
            """.trimIndent()

        val created = browser.post("/api/tasks", request).ok(201)
        val id = created["id"].asString()
        val monday = LocalDate.now(BERLIN).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        created["timing"]["span"].asString() shouldBe "WEEK"
        created["timing"]["startsOn"].asString() shouldBe monday.toString()
        created["status"].asString() shouldBe "OPEN"

        completeThenReopen(browser, id)
        editToExactTime(browser, id)
        deleteWithConfirmation(browser, id)

        val entries =
            dsl
                .selectFrom(CHANGELOG_ENTRY)
                .where(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
                .orderBy(CHANGELOG_ENTRY.ID)
                .fetch()
        entries.map { it.actorKind } shouldContainExactly List(5) { "USER" }
        entries.map { it.description } shouldContainExactly
            listOf("Created task", "Completed task", "Reopened task", "Edited task", "Deleted task")
        entries.forEach { it.fieldChanges.data() shouldNotContain "Erika" }
    }

    /** The first call asks, naming the task; the repeat with the token deletes it. */
    private fun deleteWithConfirmation(
        browser: Browser,
        id: String,
    ) {
        val first = browser.delete("/api/tasks/$id")
        first.response.status shouldBe 428
        first.body()["effect"]["name"].asString() shouldBe "Call Erika back"
        val token = first.body()["confirmationToken"].asString()
        browser.delete("/api/tasks/$id", mapOf(Confirmations.HEADER to token)).response.status shouldBe 204
        browser.get("/api/tasks/$id").response.status shouldBe 404
    }

    /** Completes the task (a stale version is a 409), completing again changes nothing, then reopens it. */
    private fun completeThenReopen(
        browser: Browser,
        id: String,
    ) {
        val done = browser.post("/api/tasks/$id/complete", """{"basedOnVersion":0}""").ok()
        done["status"].asString() shouldBe "DONE"
        done["completedAt"].isNull shouldBe false
        browser.post("/api/tasks/$id/complete", """{"basedOnVersion":1}""").ok()["version"].asInt() shouldBe 1
        browser.post("/api/tasks/$id/reopen", """{"basedOnVersion":0}""").response.status shouldBe 409

        val open = browser.post("/api/tasks/$id/reopen", """{"basedOnVersion":1}""").ok()
        open["status"].asString() shouldBe "OPEN"
        open["completedAt"].isNull shouldBe true
        open["version"].asInt() shouldBe 2
    }

    /** Replaces the bucket with an exact time in Berlin; the link is left out, so it is cleared. */
    private fun editToExactTime(
        browser: Browser,
        id: String,
    ) {
        val timing = """{"timeZone":"Europe/Berlin","localDue":"2026-10-05T10:00"}"""
        val details = """{"title":"Call Erika back","timing":$timing}"""

        val edited = browser.put("/api/tasks/$id", """{"details":$details,"basedOnVersion":2}""").ok()

        edited["timing"]["dueAt"].asString() shouldBe "2026-10-05T08:00:00Z"
        edited["timing"]["timeZone"].asString() shouldBe "Europe/Berlin"
        edited["link"].isNull shouldBe true
        edited["version"].asInt() shouldBe 3
    }

    @Test
    fun `the open tasks are grouped in the viewer's zone, an unknown zone is a 400`() {
        val browser = owner()

        fun create(bucket: String) =
            browser
                .post("/api/tasks", """{"title":"X","timing":{"timeZone":"Europe/Berlin","bucket":"$bucket"}}""")
                .ok(201)["id"]
                .asString()
        val someday = create("SOMEDAY")
        val done = create("SOMEDAY")
        browser.post("/api/tasks/$done/complete", """{"basedOnVersion":0}""").ok()
        val nextWeek = create("NEXT_WEEK")

        val groups: List<JsonNode> =
            browser
                .get("/api/tasks?timeZone=Europe/Berlin")
                .ok()
                .path("groups")
                .toList()
        val ids: Map<String, List<String>> =
            groups.associate { group ->
                group.path("group").asString() to group.path("tasks").toList().map { it.path("id").asString() }
            }

        ids.keys.toList() shouldBe
            listOf("OVERDUE", "TODAY", "THIS_WEEK", "NEXT_WEEK", "THIS_MONTH", "LATER", "SOMEDAY")
        ids.filterValues { it.isNotEmpty() } shouldBe
            mapOf("NEXT_WEEK" to listOf(nextWeek), "SOMEDAY" to listOf(someday))
        val refused = browser.get("/api/tasks?timeZone=Mars/Olympus")
        refused.response.status shouldBe 400
        refused.body()["violations"].toString() shouldBe """[{"field":"timeZone","problem":"INVALID_TIME_ZONE"}]"""
    }

    @Test
    fun `a link to something that does not exist is a 400 on the link field`() {
        val browser = owner()
        val request =
            """
            {"title":"X","timing":{"timeZone":"UTC","bucket":"SOMEDAY"},
             "link":{"type":"APPLICATION","id":"${UUID.randomUUID()}"}}
            """.trimIndent()

        val refused = browser.post("/api/tasks", request)

        refused.response.status shouldBe 400
        refused.body()["violations"].toString() shouldBe """[{"field":"link.id","problem":"NOT_FOUND"}]"""
        dsl.fetchCount(TASK) shouldBe 0
    }

    @Test
    fun `without a session every call is 401, without the CSRF token changes are 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        val task = """{"title":"X","timing":{"timeZone":"UTC","bucket":"TODAY"}}"""
        val someId = UUID.randomUUID()

        anonymous.get("/api/tasks/$someId").response.status shouldBe 401
        anonymous.get("/api/tasks?timeZone=UTC").response.status shouldBe 401
        anonymous.post("/api/tasks", task).response.status shouldBe 401
        browser.post("/api/tasks", task, csrf = null).response.status shouldBe 403
        browser.post("/api/tasks/$someId/complete", """{"basedOnVersion":0}""", csrf = null).response.status shouldBe
            403
        browser.delete("/api/tasks/$someId", csrf = null).response.status shouldBe 403
        dsl.fetchCount(TASK) shouldBe 0
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        val addresses = AtomicInteger(230)
    }
}
