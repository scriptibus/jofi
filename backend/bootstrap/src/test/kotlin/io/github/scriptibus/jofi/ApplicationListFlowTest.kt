// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
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
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.atomic.AtomicInteger

/**
 * The application list (#83) behind the real filter chain and database: a filtered, sorted and paged query
 * over applications created through the API, a 400 naming a bad parameter, and 401 without a session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class ApplicationListFlowTest(
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

    private fun MvcTestResult.ok(status: Int = 200): JsonNode =
        also { response.status shouldBe status }.let { json.readTree(it.response.contentAsString) }

    private fun Browser.company(name: String): String =
        post("/api/companies", """{"name":"$name"}""").ok(201)["id"].asString()

    private fun Browser.application(
        title: String,
        company: String,
        language: String? = null,
    ): String {
        val tone = language?.let { ""","languageAndTone":{"applicationLanguage":"$it"}""" }.orEmpty()
        return post("/api/applications", """{"title":"$title","companyId":"$company"$tone}""").ok(201)["id"].asString()
    }

    @Test
    fun `a filtered, sorted and paged list of the user's applications`() {
        val browser = owner()
        val acme = browser.company("ACME GmbH")
        val globex = browser.company("Globex")
        val kotlin = browser.application("Kotlin Backend Engineer", acme, "de-CH")
        val java = browser.application("Java Backend Engineer", acme, "de")
        browser.application("Backend Engineer", acme, "en")
        browser.application("Kotlin Backend Engineer", globex, "de")
        browser.application("Product Designer", acme, "de")

        val query = "search=backend&companyId=$acme&language=DE&status=DISCOVERED&sort=TITLE&size=1"
        val first = browser.get("/api/applications?$query").ok()
        val second = browser.get("/api/applications?$query&page=1").ok()

        first["total"].asInt() shouldBe 2
        listOf(first, second).map { it["applications"][0]["id"].asString() } shouldContainExactly listOf(java, kotlin)
        second["page"].asInt() shouldBe 1
        browser.get("/api/applications").ok()["total"].asInt() shouldBe 5
        browser.get("/api/applications?unread=true").ok()["total"].asInt() shouldBe 0
    }

    @Test
    fun `a bad parameter is a 400 naming it`() {
        val refused = owner().get("/api/applications?language=german!&size=0")

        refused.response.status shouldBe 400
        json.readTree(refused.response.contentAsString)["violations"].toString() shouldBe
            """[{"field":"language","problem":"INVALID_LANGUAGE"},{"field":"size","problem":"OUT_OF_RANGE"}]"""
    }

    @Test
    fun `without a session the list is 401`() {
        owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()

        anonymous.get("/api/applications").response.status shouldBe 401
        anonymous.get("/api/applications?search=x").response.status shouldBe 401
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger(60)
    }
}
