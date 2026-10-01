// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW_PARTICIPANT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * A company delete behind the real filter chain and database (#188): the `ON DELETE CASCADE` of its contacts removes
 * their application links and interview participations, and the delete writes the entries a single contact delete
 * would: one per application and per interview, naming every deleted contact once, with the user as actor and ids
 * only. A contact of another company stays linked and is not named.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class CompanyDeleteCascadeFlowTest(
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

    private fun MvcTestResult.body(): JsonNode = json.readTree(response.contentAsString)

    private fun Browser.created(
        path: String,
        body: String,
    ): String = post(path, body).also { it.response.status shouldBe 201 }.body()["id"].asString()

    private fun Browser.contact(
        name: String,
        company: String?,
    ): String = created("/api/contacts", """{"name":"$name"${company?.let { ""","companyId":"$it"""" }.orEmpty()}}""")

    private fun Browser.link(
        application: String,
        vararg contacts: String,
    ) {
        val body = """{"contactIds":[${contacts.joinToString { "\"$it\"" }}],"basedOnVersion":0}"""
        put("/api/applications/$application/contacts", body).response.status shouldBe 200
    }

    private fun Browser.interview(
        application: String,
        vararg participants: String,
    ): String =
        created(
            "/api/applications/$application/interviews",
            """{"type":"PHONE_SCREEN","localStart":"2099-03-01T10:00","timeZone":"Europe/Berlin",""" +
                """"participantIds":[${participants.joinToString { "\"$it\"" }}]}""",
        )

    @Test
    fun `deleting a company records the links its contacts lose on each application and interview`() {
        val browser = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        browser.post("/api/auth/first-run", """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}""")
        val acme = browser.created("/api/companies", """{"name":"ACME GmbH"}""")
        val globex = browser.created("/api/companies", """{"name":"Globex"}""")
        val erika = browser.contact("Erika Mustermann", acme)
        val max = browser.contact("Max Mustermann", acme)
        val paula = browser.contact("Paula Musterfrau", acme)
        val outsider = browser.contact("Olga Outsider", globex)
        val backend = browser.created("/api/applications", """{"title":"Backend Engineer","companyId":"$globex"}""")
        val platform = browser.created("/api/applications", """{"title":"Platform Engineer","companyId":"$globex"}""")
        browser.link(backend, erika, max, outsider)
        browser.link(platform, erika)
        val screening = browser.interview(backend, erika, paula, outsider)
        val onsite = browser.interview(platform, max)

        deleteWithConfirmation(browser, acme)

        dsl.fetchCount(APPLICATION_CONTACT) shouldBe 1
        dsl.fetchCount(INTERVIEW_PARTICIPANT) shouldBe 1
        unlinked(backend, "Unlinked a deleted contact") shouldContainExactly
            listOf("contacts" to joined(erika, max))
        unlinked(platform, "Unlinked a deleted contact") shouldContainExactly listOf("contacts" to erika)
        val removal = "Removed a deleted contact from the participants"
        unlinked(screening, removal) shouldContainExactly
            listOf("participants" to joined(erika, paula))
        unlinked(onsite, removal) shouldContainExactly listOf("participants" to max)
        actorsAndNames(listOf(backend, platform, screening, onsite)) shouldBe setOf("USER")
    }

    private fun joined(vararg ids: String): String = ids.sorted().joinToString(",")

    private fun deleteWithConfirmation(
        browser: Browser,
        company: String,
    ) {
        val first = browser.delete("/api/companies/$company")
        first.response.status shouldBe 428
        first.body()["effect"]["counts"].toString() shouldBe
            """{"applications":2,"contacts":3,"interviews":2,"tasks":0}"""
        val token = first.body()["confirmationToken"].asString()
        browser.delete("/api/companies/$company", mapOf(Confirmations.HEADER to token)).response.status shouldBe 204
    }

    /** The changes of the one entry with [description] on [entity]: field and deleted contact id each. */
    private fun unlinked(
        entity: String,
        description: String,
    ): List<Pair<String, String>> =
        dsl
            .select(CHANGELOG_ENTRY.FIELD_CHANGES)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_ID.eq(entity))
            .and(CHANGELOG_ENTRY.DESCRIPTION.eq(description))
            .fetch()
            .also { it.size shouldBe 1 }
            .flatMap { row ->
                val changes = json.readTree(row.value1().data())
                (0 until changes.size()).map { index ->
                    val change = changes[index]
                    change["after"]?.takeUnless { it.isNull } shouldBe null
                    change["field"].asString() to change["before"].asString()
                }
            }

    /** The actors of every entry on [entities]; the entries must not repeat a contact's name. */
    private fun actorsAndNames(entities: List<String>): Set<String> {
        val rows =
            dsl
                .select(CHANGELOG_ENTRY.ACTOR_KIND, CHANGELOG_ENTRY.FIELD_CHANGES, CHANGELOG_ENTRY.DESCRIPTION)
                .from(CHANGELOG_ENTRY)
                .where(CHANGELOG_ENTRY.ENTITY_ID.`in`(entities))
                .and(CHANGELOG_ENTRY.DESCRIPTION.like("%deleted contact%"))
                .fetch()
        rows.joinToString { it.value2().data() } shouldNotContain "Mustermann"
        return rows.map { it.value1() }.toSet()
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger(210)
    }
}
