// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SAVED_VIEW
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
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saved views (#99) behind the real filter chain and database: a view saved from the list's filters, listed,
 * renamed, opened as the list's query parameters, and deleted in two steps, each change recorded as the user; no
 * session is 401, no CSRF token 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class SavedViewFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun startWithoutUserOrViews() {
        dsl.deleteFrom(SAVED_VIEW).execute()
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

    private fun Browser.company(name: String): String =
        post("/api/companies", """{"name":"$name"}""").ok(201)["id"].asString()

    private fun Browser.application(
        title: String,
        company: String,
    ): String = post("/api/applications", """{"title":"$title","companyId":"$company"}""").ok(201)["id"].asString()

    @Test
    fun `save a view from filters, list, rename, open it as the list's query and delete it with confirmation`() {
        val browser = owner()
        val acme = browser.company("ACME GmbH")
        val globex = browser.company("Globex")
        val kotlin = browser.application("Kotlin Backend Engineer", acme)
        val java = browser.application("Java Backend Engineer", acme)
        browser.application("Kotlin Backend Engineer", globex)
        browser.application("Product Designer", acme)
        val filter = """{"search":"backend","companyId":"$acme","status":["DISCOVERED"],"sort":"TITLE"}"""

        val saved = browser.post(VIEWS, """{"name":"ACME backend","filter":$filter}""").ok(201)
        val id = saved["id"].asString()
        browser.post(VIEWS, """{"name":"acme BACKEND"}""").response.status shouldBe 400
        browser.get(VIEWS).ok()["views"].size() shouldBe 1
        rename(browser, id, saved["filter"])

        val opened = browser.get("$VIEWS/$id").ok()
        val listed = browser.get("/api/applications?${queryOf(opened["filter"])}").ok()
        listed["applications"].toList().map { it["id"].asString() } shouldContainExactly listOf(java, kotlin)

        deleteConfirmed(browser, id)
        browser.get("$VIEWS/$id").response.status shouldBe 404
        browser.get(VIEWS).ok()["views"].size() shouldBe 0
        dsl.fetchCount(APPLICATION) shouldBe 4
        actorsOf(id) shouldContainExactly listOf("USER", "USER", "USER")
    }

    /** Renames the view keeping its filter as read; a stale version is a 409. */
    private fun rename(
        browser: Browser,
        id: String,
        filter: JsonNode,
    ) {
        val renamed =
            browser
                .put("$VIEWS/$id", """{"view":{"name":"Backend at ACME","filter":$filter},"basedOnVersion":0}""")
                .ok()
        renamed["version"].asInt() shouldBe 1
        renamed["filter"] shouldBe filter
        browser.put("$VIEWS/$id", """{"view":{"name":"Stale"},"basedOnVersion":0}""").response.status shouldBe 409
    }

    private fun deleteConfirmed(
        browser: Browser,
        id: String,
    ) {
        val first = browser.delete("$VIEWS/$id")
        first.response.status shouldBe 428
        first.body()["effect"].toString() shouldBe """{"kind":"saved_view","name":"Backend at ACME","counts":{}}"""
        val token = first.body()["confirmationToken"].asString()
        browser.delete("$VIEWS/$id", mapOf(Confirmations.HEADER to token)).response.status shouldBe 204
    }

    @Test
    fun `without a session every call is 401, without the CSRF token changes are 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        val someId = UUID.randomUUID()

        anonymous.get(VIEWS).response.status shouldBe 401
        anonymous.get("$VIEWS/$someId").response.status shouldBe 401
        anonymous.post(VIEWS, """{"name":"Mine"}""").response.status shouldBe 401
        browser.post(VIEWS, """{"name":"Mine"}""", csrf = null).response.status shouldBe 403
        browser
            .exchange(HttpMethod.PUT, "$VIEWS/$someId", """{"view":{"name":"Mine"},"basedOnVersion":0}""", null)
            .response.status shouldBe 403
        browser.delete("$VIEWS/$someId", csrf = null).response.status shouldBe 403
        dsl.fetchCount(SAVED_VIEW) shouldBe 0
    }

    /** The list's query string for a view's filter: every property a parameter, a list's values repeated. */
    private fun queryOf(filter: JsonNode): String =
        filter
            .properties()
            .filterNot { (_, value) -> value.isNull }
            .flatMap { (name, value) ->
                if (value.isArray) value.toList().map { name to it } else listOf(name to value)
            }.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value.asString(), Charsets.UTF_8)}" }

    private fun actorsOf(id: String): List<String> =
        dsl
            .select(CHANGELOG_ENTRY.ACTOR_KIND)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
            .orderBy(CHANGELOG_ENTRY.ID)
            .fetch(CHANGELOG_ENTRY.ACTOR_KIND)

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val VIEWS = "/api/applications/saved-views"
        val addresses = AtomicInteger(180)
    }
}
