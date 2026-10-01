// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.applications.domain.InterviewRescheduled
import io.github.scriptibus.jofi.applications.domain.InterviewScheduled
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.INTERVIEW
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
 * Interviews and calls behind the real filter chain and database (#91, ADR-0048): log a phone screen with two
 * participants at the agreed Berlin time, reschedule it, add notes and the outcome afterwards, and delete it with
 * the confirmation; each step in the interview's changelog with the user as actor and without notes or participants,
 * and the application's version untouched. The upcoming list spans applications (#92), interviews are on the
 * application's timeline, and a deleted contact leaves the participants with an entry on the interview's changelog.
 * No session is 401, no CSRF token 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(PostgresTestConfiguration::class)
class InterviewFlowTest(
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
    ): JsonNode = post(path, body).also { it.response.status shouldBe 201 }.body()

    @Test
    fun `log, reschedule, add notes and the outcome, then delete with confirmation`(events: ApplicationEvents) {
        val browser = owner()
        val company = browser.created("/api/companies", """{"name":"ACME GmbH"}""")["id"].asString()
        val application =
            browser
                .created("/api/applications", """{"title":"Backend Engineer","companyId":"$company"}""")["id"]
                .asString()
        val erika = browser.created("/api/contacts", """{"name":"Erika Mustermann","companyId":"$company"}""")
        val max = browser.created("/api/contacts", """{"name":"Max Mustermann"}""")
        val participants = listOf(erika, max).map { it["id"].asString() }
        val path = "/api/applications/$application/interviews"

        val logged = browser.created(path, details("2026-10-05T10:00", participants))
        logged["startsAt"].asString() shouldBe "2026-10-05T08:00:00Z"
        logged["localStart"].asString() shouldBe "2026-10-05T10:00:00"
        logged["participantIds"].items().map { it.asString() } shouldContainExactlyInAnyOrder participants
        val interview = "$path/${logged["id"].asString()}"

        rescheduleThenAddNotes(browser, path, interview, participants)
        browser.get("/api/applications/$application").ok()["version"].asInt() shouldBe 0

        deleteWithConfirmation(browser, interview)
        browser.get(interview).response.status shouldBe 404
        dsl.fetchCount(CONTACT) shouldBe 2
        changelogOf(logged["id"].asString())
        events.stream(InterviewScheduled::class.java).count() shouldBe 1
        events.stream(InterviewRescheduled::class.java).count() shouldBe 1
        deleteApplicationWithItsInterview(browser, application, participants)
    }

    /** The application delete's effect counts the interviews that go with it, and the delete removes them. */
    private fun deleteApplicationWithItsInterview(
        browser: Browser,
        application: String,
        participants: List<String>,
    ) {
        browser.created("/api/applications/$application/interviews", details("2026-10-12T09:00", participants))
        val first = browser.delete("/api/applications/$application")
        first.response.status shouldBe 428
        first.body()["effect"]["counts"]["interviews"].asInt() shouldBe 1
        val token = first.body()["confirmationToken"].asString()
        browser.delete("/api/applications/$application", mapOf(Confirmations.HEADER to token)).response.status shouldBe
            204
        dsl.fetchCount(INTERVIEW) shouldBe 0
        dsl.fetchCount(CONTACT) shouldBe 2
    }

    /** Moves the interview (version 0), then adds notes and the outcome (version 1); a stale version is a 409. */
    private fun rescheduleThenAddNotes(
        browser: Browser,
        path: String,
        interview: String,
        participants: List<String>,
    ) {
        val moved =
            browser.put(
                interview,
                """{"details":${details("2026-10-06T14:30", participants)},"basedOnVersion":0}""",
            )
        moved.ok()["startsAt"].asString() shouldBe "2026-10-06T12:30:00Z"
        val afterwards = details("2026-10-06T14:30", participants, ""","notes":"Went well","outcome":"PASSED"""")
        val done = browser.put(interview, """{"details":$afterwards,"basedOnVersion":1}""").ok()
        done["notes"].asString() shouldBe "Went well"
        done["version"].asInt() shouldBe 2
        browser.put(interview, """{"details":$afterwards,"basedOnVersion":1}""").response.status shouldBe 409
        browser
            .get(path)
            .ok()["interviews"]
            .items()
            .map { it["outcome"].asString() } shouldContainExactly
            listOf("PASSED")
    }

    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun details(
        localStart: String,
        participants: List<String>,
        more: String = "",
    ): String =
        """{"type":"PHONE_SCREEN","localStart":"$localStart","timeZone":"Europe/Berlin",""" +
            """"participantIds":[${participants.joinToString {
                "\"$it\""
            }}],"preparationNotes":"Ask about on-call"$more}"""

    private fun deleteWithConfirmation(
        browser: Browser,
        interview: String,
    ) {
        val first = browser.delete(interview)
        first.response.status shouldBe 428
        val problem = first.body()
        problem["effect"]["name"].asString() shouldBe "PHONE_SCREEN 2026-10-06T14:30 Europe/Berlin"
        val token = problem["confirmationToken"].asString()
        browser.delete(interview, mapOf(Confirmations.HEADER to token)).response.status shouldBe 204
    }

    /** Every step is in the interview's changelog as the user's, with values only for type, time, zone and outcome. */
    private fun changelogOf(interview: String) {
        val entries =
            dsl
                .select(CHANGELOG_ENTRY.DESCRIPTION, CHANGELOG_ENTRY.FIELD_CHANGES, CHANGELOG_ENTRY.ACTOR_KIND)
                .from(CHANGELOG_ENTRY)
                .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq("interview"))
                .and(CHANGELOG_ENTRY.ENTITY_ID.eq(interview))
                .orderBy(CHANGELOG_ENTRY.ID)
                .fetch()
        entries.map { it.value1() } shouldContainExactly
            listOf(
                "Logged interview; also changed: participants, preparation notes",
                "Edited interview",
                "Edited interview; also changed: notes",
                "Deleted interview",
            )
        entries.map { it.value3() }.toSet() shouldBe setOf("USER")
        entries.joinToString { it.value2().data() }.let {
            it shouldNotContain "Went well"
            it shouldNotContain "on-call"
        }
    }

    @Test
    fun `upcoming interviews span applications, show on the timeline and lose a deleted contact`() {
        val browser = owner()
        val company = browser.created("/api/companies", """{"name":"ACME GmbH"}""")["id"].asString()
        val backend = application(browser, company, "Backend Engineer")
        val platform = application(browser, company, "Platform Engineer")
        val erika = browser.created("/api/contacts", """{"name":"Erika Mustermann"}""")["id"].asString()
        val later = logged(browser, backend, "2099-03-01T10:00", listOf(erika))
        val sooner = logged(browser, platform, "2099-02-01T09:00")
        logged(browser, backend, "2020-01-06T10:00", listOf(erika))
        logged(browser, platform, "2099-01-01T09:00", more = ""","outcome":"CANCELLED"""")

        val upcoming = browser.get("/api/interviews/upcoming").ok()["interviews"].items()
        upcoming.map { it["interview"]["id"].asString() to it["applicationTitle"].asString() } shouldContainExactly
            listOf(sooner to "Platform Engineer", later to "Backend Engineer")
        val timeline = browser.get("/api/applications/$backend/timeline").ok()["entries"].items()
        val interviews = timeline.filter { it["kind"].asString() == "INTERVIEW" }
        interviews.map { it["interview"]["localStart"].asString() } shouldBe
            listOf("2099-03-01T10:00:00", "2020-01-06T10:00:00")

        deleteContact(browser, erika)
        val participants = browser.get("/api/applications/$backend/interviews/$later").ok()["participantIds"]
        participants.items().shouldBeEmpty()
        participantRemovals(later) shouldContainExactly listOf("USER" to erika)
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        anonymous.get("/api/interviews/upcoming").response.status shouldBe 401
    }

    /** Logs an interview at [localStart] Berlin time and answers its id. */
    private fun logged(
        browser: Browser,
        application: String,
        localStart: String,
        participants: List<String> = emptyList(),
        more: String = "",
    ): String =
        browser
            .created(
                "/api/applications/$application/interviews",
                details(localStart, participants, more),
            )["id"]
            .asString()

    private fun application(
        browser: Browser,
        company: String,
        title: String,
    ): String = browser.created("/api/applications", """{"title":"$title","companyId":"$company"}""")["id"].asString()

    private fun deleteContact(
        browser: Browser,
        contact: String,
    ) {
        val first = browser.delete("/api/contacts/$contact")
        first.response.status shouldBe 428
        val token = first.body()["confirmationToken"].asString()
        browser.delete("/api/contacts/$contact", mapOf(Confirmations.HEADER to token)).response.status shouldBe 204
    }

    /** The interview's changelog entries for a removed participant: the actor and the contact id it names. */
    private fun participantRemovals(interview: String): List<Pair<String, String>> =
        dsl
            .select(CHANGELOG_ENTRY.ACTOR_KIND, CHANGELOG_ENTRY.FIELD_CHANGES)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq("interview"))
            .and(CHANGELOG_ENTRY.ENTITY_ID.eq(interview))
            .and(CHANGELOG_ENTRY.DESCRIPTION.eq("Removed a deleted contact from the participants"))
            .fetch()
            .map { row ->
                val change = json.readTree(row.value2().data())[0]
                change["field"].asString() shouldBe "participants"
                change["after"]?.takeUnless { it.isNull } shouldBe null
                row.value1() to change["before"].asString()
            }

    @Test
    fun `without a session the interview calls are 401, without the CSRF token the writes are 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        val path = "/api/applications/${UUID.randomUUID()}/interviews"
        val body = """{"type":"HR","localStart":"2026-10-05T10:00","timeZone":"UTC"}"""

        anonymous.get(path).response.status shouldBe 401
        anonymous.post(path, body).response.status shouldBe 401
        browser.post(path, body, csrf = null).response.status shouldBe 403
        browser
            .exchange(HttpMethod.PUT, "$path/${UUID.randomUUID()}", """{"details":$body,"basedOnVersion":0}""", null)
            .response.status shouldBe 403
        browser.delete("$path/${UUID.randomUUID()}", csrf = null).response.status shouldBe 403
        browser.get(path).response.status shouldBe 404
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger(160)
    }
}
