// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.companies.domain.ContactDeleted
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT_CHANNEL
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The contact API behind the real filter chain and database (#89): create, search, edit the channels
 * and the two-step delete that leaves nothing personal behind (spec §13), each recorded with the user
 * as actor; no session is 401, no CSRF token 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(PostgresTestConfiguration::class)
class ContactFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun startWithoutUserOrContacts() {
        dsl.deleteFrom(APPLICATION).execute()
        dsl.deleteFrom(CONTACT).execute()
        dsl.deleteFrom(COMPANY).execute()
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
    }

    private fun owner(): Browser =
        Browser(mvc, "203.0.113.${addresses.incrementAndGet()}").open().also {
            val body = """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}"""
            it.post("/api/auth/first-run", body).response.status shouldBe 204
        }

    private fun MvcTestResult.body(): JsonNode = json.readTree(response.contentAsString)

    private fun Browser.created(
        path: String,
        body: String,
    ): String = post(path, body).also { it.response.status shouldBe 201 }.body()["id"].asString()

    private fun changelogOf(id: String): List<String> =
        dsl
            .select(CHANGELOG_ENTRY.ACTOR_KIND, CHANGELOG_ENTRY.DESCRIPTION, CHANGELOG_ENTRY.FIELD_CHANGES)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
            .orderBy(CHANGELOG_ENTRY.ID)
            .fetch { "${it.value1()} ${it.value2()} ${it.value3()}" }

    @Test
    fun `create, search, edit channels and delete with confirmation, leaving only ids behind`(
        events: ApplicationEvents,
    ) {
        val browser = owner()
        val company = browser.created("/api/companies", """{"name":"ACME GmbH"}""")
        val id =
            browser.created(
                "/api/contacts",
                """{"name":"Erika Mustermann","companyId":"$company","relationshipNotes":"Met at the fair",
                   "channels":[{"kind":"EMAIL","value":"erika@acme.example","label":"work"}]}""",
            )
        browser.created("/api/contacts", """{"name":"Max Mustermann"}""")

        val found = browser.get("/api/contacts?search=erika&companyId=$company").body()
        found["total"].asInt() shouldBe 1
        found["contacts"][0]["channels"][0]["value"].asString() shouldBe "erika@acme.example"

        editChannels(browser, id, company)
        val application = linkedApplication(company, id)
        deleteWithConfirmation(browser, id)

        browser.get("/api/contacts/$id").response.status shouldBe 404
        val contact = UUID.fromString(id)
        nothingPersonalLeft(contact, application)
        events.stream(ContactDeleted::class.java).map { it.contact.value }.toList() shouldContainExactly listOf(contact)
    }

    @Test
    fun `a company that does not exist is a 400 naming the field`() {
        val browser = owner()

        val refused = browser.post("/api/contacts", """{"name":"Erika","companyId":"${UUID.randomUUID()}"}""")

        refused.response.status shouldBe 400
        refused.body()["violations"].toString() shouldBe """[{"field":"companyId","problem":"NOT_FOUND"}]"""
        dsl.fetchCount(CONTACT) shouldBe 0
    }

    @Test
    fun `without a session every call is 401, without the CSRF token changes are 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "203.0.113.${addresses.incrementAndGet()}").open()

        anonymous.get("/api/contacts").response.status shouldBe 401
        anonymous.post("/api/contacts", """{"name":"Erika"}""").response.status shouldBe 401
        browser.post("/api/contacts", """{"name":"Erika"}""", csrf = null).response.status shouldBe 403
        browser
            .exchange(
                HttpMethod.PUT,
                "/api/contacts/${UUID.randomUUID()}",
                """{"details":{"name":"x"},"basedOnVersion":0}""",
                null,
            ).response.status shouldBe 403
        browser.delete("/api/contacts/${UUID.randomUUID()}", csrf = null).response.status shouldBe 403
        dsl.fetchCount(CONTACT) shouldBe 0
    }

    private fun editChannels(
        browser: Browser,
        id: String,
        company: String,
    ) {
        val edit =
            """{"details":{"name":"Erika Mustermann","companyId":"$company","channels":[
               {"kind":"PHONE","value":"+49 30 123"},{"kind":"WEB","value":"https://www.xing.com/profile/Erika"}]},
               "basedOnVersion":0}"""
        val edited = browser.put("/api/contacts/$id", edit).also { it.response.status shouldBe 200 }.body()
        edited["version"].asInt() shouldBe 1
        (0 until 2).map { edited["channels"][it]["kind"].asString() } shouldContainExactly listOf("PHONE", "WEB")
        browser.put("/api/contacts/$id", edit).response.status shouldBe 409
    }

    private fun deleteWithConfirmation(
        browser: Browser,
        id: String,
    ) {
        val first = browser.delete("/api/contacts/$id")
        first.response.status shouldBe 428
        first.body()["effect"].toString() shouldBe
            """{"kind":"contact","name":"Erika Mustermann","counts":{"applications":1,"interviews":0}}"""
        val token = first.body()["confirmationToken"].asString()
        browser.delete("/api/contacts/$id", mapOf(Confirmations.HEADER to token)).response.status shouldBe 204
    }

    /** Rows, channels and links are gone; the changelog keeps ids and no personal data (spec §13). */
    private fun nothingPersonalLeft(
        contact: UUID,
        application: UUID,
    ) {
        dsl.fetchCount(CONTACT, CONTACT.ID.eq(contact)) shouldBe 0
        dsl.fetchCount(CONTACT_CHANNEL, CONTACT_CHANNEL.CONTACT_ID.eq(contact)) shouldBe 0
        dsl.fetchCount(APPLICATION_CONTACT) shouldBe 0
        val history = changelogOf(contact.toString())
        history.map { it.substringBefore(' ') } shouldContainExactly listOf("USER", "USER", "USER")
        val unlinked = changelogOf(application.toString()).single()
        unlinked shouldContain contact.toString()
        (history + unlinked).forEach { entry ->
            listOf("Erika", "erika@", "+49", "xing", "fair").forEach { entry shouldNotContain it }
        }
    }

    /** An application of [company] linked to [contact] (the link API comes with #90). */
    private fun linkedApplication(
        company: String,
        contact: String,
    ): UUID {
        val id = UUID.randomUUID()
        dsl
            .insertInto(APPLICATION)
            .set(APPLICATION.ID, id)
            .set(APPLICATION.COMPANY_ID, UUID.fromString(company))
            .set(APPLICATION.TITLE, "Backend Engineer")
            .set(APPLICATION.CREATED_AT, NOW)
            .set(APPLICATION.UPDATED_AT, NOW)
            .execute()
        dsl
            .insertInto(APPLICATION_CONTACT, APPLICATION_CONTACT.APPLICATION_ID, APPLICATION_CONTACT.CONTACT_ID)
            .values(id, UUID.fromString(contact))
            .execute()
        return id
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-09-30T08:00:00Z")
        val addresses = AtomicInteger(200)
    }
}
