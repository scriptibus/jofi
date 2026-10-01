// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.companies.application.port.CompanyRepositoryPort
import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPreferenceChanged
import io.github.scriptibus.jofi.companies.domain.CompanyStoreResult
import io.github.scriptibus.jofi.companies.domain.ContactDeleted
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
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
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The company API behind the real filter chain and database (#88): create, fuzzy search, flag and the
 * two-step delete that takes the company's contacts along, each recorded with the user as actor; no
 * session is 401, no CSRF token 403, and applications block the delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(PostgresTestConfiguration::class)
class CompanyFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun startWithoutUserOrCompanies() {
        dsl.deleteFrom(APPLICATION).execute()
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

    private fun Browser.create(name: String): JsonNode =
        post("/api/companies", """{"name":"$name"}""").also { it.response.status shouldBe 201 }.body()

    private fun actorsOf(id: String): List<String> =
        dsl
            .select(CHANGELOG_ENTRY.ACTOR_KIND)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
            .orderBy(CHANGELOG_ENTRY.ID)
            .fetch(CHANGELOG_ENTRY.ACTOR_KIND)

    @Test
    fun `create, search, flag and delete with confirmation, each recorded as the user`(events: ApplicationEvents) {
        val browser = owner()
        val acme = browser.create("ACME GmbH")
        browser.create("Globex")
        val id = acme["id"].asString()

        val found = browser.get("/api/companies?search=acme").body()
        val companies = found["companies"]
        (0 until companies.size()).map { companies[it]["name"].asString() } shouldContainExactly listOf("ACME GmbH")
        found["companies"][0]["applicationCount"].asInt() shouldBe 0

        val flagged = browser.put("/api/companies/$id/preference", """{"preference":"FAVOURITE","basedOnVersion":0}""")
        flagged.response.status shouldBe 200
        flagged.body()["version"].asInt() shouldBe 1
        events.stream(CompanyPreferenceChanged::class.java).count() shouldBe 1
        browser.get("/api/companies?preference=FAVOURITE").body()["total"].asInt() shouldBe 1

        val contact = contactOf(id)
        val first = browser.delete("/api/companies/$id")
        first.response.status shouldBe 428
        first.body()["effect"].toString() shouldBe
            """{"kind":"company","name":"ACME GmbH","counts":{"applications":0,"contacts":1,"interviews":0,"tasks":0}}"""
        val token = first.body()["confirmationToken"].asString()
        browser.delete("/api/companies/$id", mapOf(Confirmations.HEADER to token)).response.status shouldBe 204

        browser.get("/api/companies/$id").response.status shouldBe 404
        dsl.fetchCount(CONTACT, CONTACT.ID.eq(contact)) shouldBe 0
        actorsOf(id) shouldContainExactly listOf("USER", "USER", "USER")
        actorsOf(contact.toString()) shouldContainExactly listOf("USER")
        events.stream(ContactDeleted::class.java).map { it.contact.value }.toList() shouldContainExactly listOf(contact)
    }

    @Test
    fun `a company with applications cannot be deleted`() {
        val browser = owner()
        val id = browser.create("ACME GmbH")["id"].asString()
        insertApplication(id)

        browser.get("/api/companies/$id").body()["applicationCount"].asInt() shouldBe 1
        val refused = browser.delete("/api/companies/$id")
        refused.response.status shouldBe 409
        refused.body()["type"].asString() shouldBe "urn:jofi:problem:companies:has-applications"
    }

    @Test
    fun `the store maps the applications foreign key by name behind Spring's exception translation`() {
        val browser = owner()
        val id = CompanyId(UUID.fromString(browser.create("ACME GmbH")["id"].asString()))
        insertApplication(id.value.toString())
        val gate = context.getBean(ConfirmActionUseCase::class.java)
        val requester = ConfirmationRequester(Actor.User, "session")
        val action =
            ConfirmableAction(
                Company.DELETE_OPERATION,
                listOf(id.value.toString()),
                ConfirmationEffect("company", "ACME"),
            )
        val token = (gate.execute(ConfirmationRequest(requester, action, null)) as ConfirmationResult.Required).token
        val proof = gate.execute(ConfirmationRequest(requester, action, token)) as ConfirmationResult.Confirmed

        context.getBean(CompanyRepositoryPort::class.java).delete(id, proof) shouldBe CompanyStoreResult.HasApplications
    }

    @Test
    fun `without a session every call is 401, without the CSRF token changes are 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "203.0.113.${addresses.incrementAndGet()}").open()

        anonymous.get("/api/companies").response.status shouldBe 401
        anonymous.post("/api/companies", """{"name":"ACME"}""").response.status shouldBe 401
        browser.post("/api/companies", """{"name":"ACME"}""", csrf = null).response.status shouldBe 403
        browser
            .exchange(
                HttpMethod.PUT,
                "/api/companies/${UUID.randomUUID()}",
                """{"details":{"name":"x"},"basedOnVersion":0}""",
                null,
            ).response.status shouldBe 403
        browser.delete("/api/companies/${UUID.randomUUID()}", csrf = null).response.status shouldBe 403
        dsl.fetchCount(COMPANY) shouldBe 0
    }

    private fun insertApplication(company: String) {
        dsl
            .insertInto(APPLICATION)
            .set(APPLICATION.ID, UUID.randomUUID())
            .set(APPLICATION.COMPANY_ID, UUID.fromString(company))
            .set(APPLICATION.TITLE, "Backend Engineer")
            .set(APPLICATION.CREATED_AT, NOW)
            .set(APPLICATION.UPDATED_AT, NOW)
            .execute()
    }

    private fun contactOf(company: String): UUID {
        val id = UUID.randomUUID()
        dsl
            .insertInto(CONTACT, CONTACT.ID, CONTACT.COMPANY_ID, CONTACT.NAME, CONTACT.CREATED_AT, CONTACT.UPDATED_AT)
            .values(id, UUID.fromString(company), "Erika Mustermann", NOW, NOW)
            .execute()
        return id
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-09-30T08:00:00Z")
        val addresses = AtomicInteger(100)
    }
}
