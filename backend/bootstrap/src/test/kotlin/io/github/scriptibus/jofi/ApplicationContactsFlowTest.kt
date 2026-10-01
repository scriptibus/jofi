// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contact links behind the real filter chain and database (#90): link two contacts, find the application by
 * either in the list, unlink one, and delete the other, whose link goes by `ON DELETE CASCADE` while the
 * contact delete writes the application's entry. Every change is in the application's changelog with the user
 * as actor and ids only; an unknown contact is a 400 on `contactIds`, a stale version a 409; no session is
 * 401, no CSRF token 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class ApplicationContactsFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun startWithoutUserOrApplications() {
        dsl.deleteFrom(APPLICATION).execute()
        dsl.deleteFrom(CONTACT).execute()
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

    private fun MvcTestResult.ok(): JsonNode = also { response.status shouldBe 200 }.body()

    private fun Browser.created(
        path: String,
        body: String,
    ): String = post(path, body).also { it.response.status shouldBe 201 }.body()["id"].asString()

    private fun Browser.link(
        application: String,
        version: Int,
        vararg contacts: String,
    ): MvcTestResult =
        put(
            "/api/applications/$application/contacts",
            """{"contactIds":[${contacts.joinToString { "\"$it\"" }}],"basedOnVersion":$version}""",
        )

    private fun Browser.foundBy(contact: String): List<String> {
        val page = get("/api/applications?contactId=$contact").ok()["applications"]
        return (0 until page.size()).map { page[it]["id"].asString() }
    }

    @Test
    fun `link two contacts, find the application by each, unlink one and delete the other`() {
        val browser = owner()
        val company = browser.created("/api/companies", """{"name":"ACME GmbH"}""")
        val id = browser.created("/api/applications", """{"title":"Backend Engineer","companyId":"$company"}""")
        val erika = browser.created("/api/contacts", """{"name":"Erika Mustermann","companyId":"$company"}""")
        val max = browser.created("/api/contacts", """{"name":"Max Mustermann"}""")

        val linked = browser.link(id, 0, max, erika).ok()
        val ids = linked["contactIds"]
        (0 until ids.size()).map { ids[it].asString() }.toSet() shouldBe setOf(erika, max)
        linked["version"].asInt() shouldBe 1
        browser.foundBy(erika) shouldContainExactly listOf(id)
        browser.foundBy(max) shouldContainExactly listOf(id)

        refusals(browser, id)
        browser.link(id, 1, max).ok()["version"].asInt() shouldBe 2
        browser.foundBy(erika) shouldContainExactly emptyList()
        browser.link(id, 2, max).ok()["version"].asInt() shouldBe 2

        deleteContact(browser, max)
        dsl.fetchCount(APPLICATION_CONTACT) shouldBe 0
        browser.get("/api/applications/$id").ok()["contactIds"].size() shouldBe 0
        changelogOf(id, erika, max)
    }

    /** An unknown contact is a 400 on `contactIds`, a stale version a 409; neither changes anything. */
    private fun refusals(
        browser: Browser,
        id: String,
    ) {
        val unknown = browser.link(id, 1, UUID.randomUUID().toString())
        unknown.response.status shouldBe 400
        unknown.body()["violations"].toString() shouldBe """[{"field":"contactIds","problem":"NOT_FOUND"}]"""
        val stale = browser.link(id, 0)
        stale.response.status shouldBe 409
        stale.body()["type"].asString() shouldBe "urn:jofi:problem:applications:version-conflict"
        browser.get("/api/applications/$id").ok()["version"].asInt() shouldBe 1
    }

    private fun deleteContact(
        browser: Browser,
        contact: String,
    ) {
        val first = browser.delete("/api/contacts/$contact")
        first.response.status shouldBe 428
        first.body()["effect"]["counts"].toString() shouldBe """{"applications":1,"interviews":0}"""
        val token = first.body()["confirmationToken"].asString()
        browser.delete("/api/contacts/$contact", mapOf(Confirmations.HEADER to token)).response.status shouldBe 204
    }

    /** One entry per change, all by the user, naming contacts by id only. */
    private fun changelogOf(
        id: String,
        erika: String,
        max: String,
    ) {
        val entries =
            dsl
                .select(CHANGELOG_ENTRY.DESCRIPTION, CHANGELOG_ENTRY.FIELD_CHANGES, CHANGELOG_ENTRY.ACTOR_KIND)
                .from(CHANGELOG_ENTRY)
                .where(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
                .orderBy(CHANGELOG_ENTRY.ID)
                .fetch()
        entries.map { it.value1() } shouldContainExactly
            listOf("Created application") + List(2) { "Changed linked contacts" } + "Unlinked a deleted contact"
        entries.map { it.value3() }.toSet() shouldBe setOf("USER")
        entries.drop(1).map { entry ->
            json.readTree(entry.value2().data()).single().let { listOf(it["before"], it["after"]).map(::text) }
        } shouldContainExactly
            listOf(
                listOf(null, listOf(erika, max).sorted().joinToString(",")),
                listOf(erika, null),
                listOf(max, null),
            )
        entries.joinToString { it.value2().data() }.contains("Mustermann") shouldBe false
    }

    private fun text(node: JsonNode?): String? = node?.takeUnless { it.isNull }?.asString()

    @Test
    fun `without a session linking is 401, without the CSRF token 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        val path = "/api/applications/${UUID.randomUUID()}/contacts"
        val body = """{"contactIds":[],"basedOnVersion":0}"""

        anonymous.put(path, body).response.status shouldBe 401
        browser.exchange(HttpMethod.PUT, path, body, null).response.status shouldBe 403
        browser.put(path, body).response.status shouldBe 404
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger(240)
    }
}
