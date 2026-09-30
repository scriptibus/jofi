// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.applications.domain.ApplicationDeleted
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The application API behind the real filter chain and database (#82): create, read, edit, mark unread
 * and read, and the two-step delete, each recorded with the user as actor; an unknown company is a 400
 * found by its foreign key through Spring's exception translation; no session is 401, no CSRF token 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(PostgresTestConfiguration::class)
class ApplicationFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun startWithoutUserOrApplications() {
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

    private fun actorsOf(id: String): List<String> =
        dsl
            .select(CHANGELOG_ENTRY.ACTOR_KIND)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
            .orderBy(CHANGELOG_ENTRY.ID)
            .fetch(CHANGELOG_ENTRY.ACTOR_KIND)

    @Test
    fun `create, read, edit, mark read and delete with confirmation, each recorded as the user`(
        events: ApplicationEvents,
    ) {
        val browser = owner()
        val company = browser.post("/api/companies", """{"name":"ACME GmbH"}""").ok(201)["id"].asString()

        val created =
            browser
                .post("/api/applications", """{"title":"Backend Engineer","companyId":"$company","remoteShare":60}""")
                .ok(201)
        val id = created["id"].asString()
        created["status"].asString() shouldBe "DISCOVERED"
        browser.get("/api/applications/$id").ok()["remoteShare"].asInt() shouldBe 60

        editThenMarkRead(browser, id, company)

        val first = browser.delete("/api/applications/$id")
        first.response.status shouldBe 428
        first.body()["effect"].toString() shouldBe
            """{"kind":"application","name":"Staff Engineer","counts":""" +
            """{"contactLinks":0,"snapshots":0,"sources":0,"statusChanges":1}}"""
        val token = first.body()["confirmationToken"].asString()
        browser.delete("/api/applications/$id", mapOf(Confirmations.HEADER to token)).response.status shouldBe 204

        browser.get("/api/applications/$id").response.status shouldBe 404
        dsl.fetchCount(APPLICATION_STATUS_CHANGE) shouldBe 0
        actorsOf(id) shouldContainExactly listOf("USER", "USER", "USER", "USER", "USER")
        events.stream(ApplicationDeleted::class.java).map { it.application.value }.toList() shouldContainExactly
            listOf(UUID.fromString(id))
    }

    /** Edits the details (a stale version is a 409), then marks the application unread and read again. */
    private fun editThenMarkRead(
        browser: Browser,
        id: String,
        company: String,
    ) {
        val edited =
            browser
                .put(
                    "/api/applications/$id",
                    """{"details":{"title":"Staff Engineer","companyId":"$company"},"basedOnVersion":0}""",
                ).ok()
        edited["version"].asInt() shouldBe 1
        edited["remoteShare"].isNull shouldBe true
        browser
            .put("/api/applications/$id", """{"details":{"title":"Lead","companyId":"$company"},"basedOnVersion":0}""")
            .response.status shouldBe 409

        browser.put("/api/applications/$id/unread", """{"unread":true}""").ok()["unread"].asBoolean() shouldBe true
        val read = browser.put("/api/applications/$id/unread", """{"unread":false}""").ok()
        read["unread"].asBoolean() shouldBe false
        read["version"].asInt() shouldBe 1
    }

    @Test
    fun `an unknown company is a 400 on the company field`() {
        val browser = owner()

        val refused = browser.post("/api/applications", """{"title":"X","companyId":"${UUID.randomUUID()}"}""")

        refused.response.status shouldBe 400
        refused.body()["violations"].toString() shouldBe """[{"field":"companyId","problem":"NOT_FOUND"}]"""
        dsl.fetchCount(APPLICATION) shouldBe 0
    }

    @Test
    fun `without a session every call is 401, without the CSRF token changes are 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        val someId = UUID.randomUUID()

        anonymous.get("/api/applications/$someId").response.status shouldBe 401
        anonymous.post("/api/applications", """{"title":"X","companyId":"$someId"}""").response.status shouldBe 401
        browser
            .post(
                "/api/applications",
                """{"title":"X","companyId":"$someId"}""",
                csrf = null,
            ).response.status shouldBe
            403
        browser
            .exchange(HttpMethod.PUT, "/api/applications/$someId/unread", """{"unread":false}""", null)
            .response.status shouldBe 403
        browser.delete("/api/applications/$someId", csrf = null).response.status shouldBe 403
        dsl.fetchCount(APPLICATION) shouldBe 0
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger(10)
    }
}
