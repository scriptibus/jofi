// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStatusChanged
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_DESCRIPTION_SNAPSHOT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
 * The status pipeline behind the real filter chain and database (#84): moving an application through the
 * pipeline, declining with a reason, correcting it and reopening, each move in the history and the changelog
 * with the user as actor; applying freezes the job description once (ADR-0046); a forbidden move and a stale
 * version are 409; no session is 401, no CSRF token 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(PostgresTestConfiguration::class)
class ApplicationStatusFlowTest(
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

    private fun MvcTestResult.ok(): JsonNode = also { response.status shouldBe 200 }.body()

    /** Moves application [id] from [version] as [body] (without the version) says; the new application. */
    private fun Browser.move(
        id: String,
        version: Int,
        body: String,
    ): JsonNode = put("/api/applications/$id/status", """{$body,"basedOnVersion":$version}""").ok()

    @Test
    fun `move through the pipeline, decline, correct the reason and reopen, all in the history`(
        events: ApplicationEvents,
    ) {
        val browser = owner()
        val company = browser.post("/api/companies", """{"name":"ACME GmbH"}""").body()["id"].asString()
        val created = browser.post("/api/applications", """{"title":"Backend Engineer","companyId":"$company"}""")
        val id = created.body()["id"].asString()
        val source = source(id)
        val applied = snapshot(source, "Posting as found")

        browser.move(id, 0, """"status":"SHORTLISTED"""")
        browser.move(id, 1, """"status":"APPLIED","reason":"Sent CV and cover letter"""")
        frozen(applied) shouldNotBe null
        val later = snapshot(source, "Posting changed after applying")
        declineCorrectAndReopen(browser, id)

        historyOf(browser, id)
        frozen(later) shouldBe null
        changelogOf(id, applied)
        events
            .stream(ApplicationStatusChanged::class.java)
            .map { it.from to it.to }
            .toList()
            .last() shouldBe (ApplicationStatus.DECLINED to ApplicationStatus.APPLIED)
    }

    /** From Applied (version 2) on: interviews, the offer, refusals, declining, the correction and applying again. */
    private fun declineCorrectAndReopen(
        browser: Browser,
        id: String,
    ) {
        browser.move(id, 2, """"status":"INTERVIEWING"""")
        browser.move(id, 3, """"status":"OFFER"""")
        refusals(browser, id)
        browser.move(id, 4, """"status":"DECLINED","reason":"Salary too low","declineCategory":"SALARY"""")
        val correction = """"status":"DECLINED","reason":"Took another","declineCategory":"OTHER_OFFER""""
        val corrected = browser.move(id, 5, correction)
        corrected["declineReason"].toString() shouldBe """{"category":"OTHER_OFFER","text":"Took another"}"""
        val reopened = browser.move(id, 6, """"status":"APPLIED"""")
        reopened["status"].asString() shouldBe "APPLIED"
        reopened["declineReason"].isNull shouldBe true
        reopened["version"].asInt() shouldBe 7
    }

    /** The history holds every move, oldest first, with reasons and categories, all by the user. */
    private fun historyOf(
        browser: Browser,
        id: String,
    ) {
        val changes = browser.get("/api/applications/$id/status-history").ok()["changes"]
        val history = (0 until changes.size()).map { changes[it] }
        history.map { change ->
            change["from"].takeUnless { it.isNull }?.asString() to change["to"].asString()
        } shouldContainExactly
            listOf<Pair<String?, String>>(
                null to "DISCOVERED",
                "DISCOVERED" to "SHORTLISTED",
                "SHORTLISTED" to "APPLIED",
                "APPLIED" to "INTERVIEWING",
                "INTERVIEWING" to "OFFER",
                "OFFER" to "DECLINED",
                "DECLINED" to "DECLINED",
                "DECLINED" to "APPLIED",
            )
        history[5]["reason"].asString() shouldBe "Salary too low"
        history[6]["declineCategory"].asString() shouldBe "OTHER_OFFER"
        history.map { it["actor"]["kind"].asString() }.toSet() shouldBe setOf("USER")
    }

    /** A move the matrix forbids and a stale version are 409s that change nothing; a missing category is a 400. */
    private fun refusals(
        browser: Browser,
        id: String,
    ) {
        val forbidden = browser.put("/api/applications/$id/status", """{"status":"WITHDRAWN","basedOnVersion":4}""")
        forbidden.response.status shouldBe 409
        forbidden.body()["type"].asString() shouldBe "urn:jofi:problem:applications:invalid-transition"
        val stale = browser.put("/api/applications/$id/status", """{"status":"ACCEPTED","basedOnVersion":3}""")
        stale.response.status shouldBe 409
        stale.body()["type"].asString() shouldBe "urn:jofi:problem:applications:version-conflict"
        browser
            .put("/api/applications/$id/status", """{"status":"DECLINED","basedOnVersion":4}""")
            .response.status shouldBe 400
    }

    /** The application's changelog names status and category changes, never reason texts; one entry per freeze. */
    private fun changelogOf(
        id: String,
        applied: UUID,
    ) {
        val entries =
            dsl
                .select(CHANGELOG_ENTRY.DESCRIPTION, CHANGELOG_ENTRY.FIELD_CHANGES, CHANGELOG_ENTRY.ACTOR_KIND)
                .from(CHANGELOG_ENTRY)
                .where(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
                .orderBy(CHANGELOG_ENTRY.ID)
                .fetch()
        entries.map { it.value1() } shouldContainExactly
            listOf("Created application") + List(5) { "Changed application status" } +
            listOf("Corrected decline reason", "Changed application status")
        entries.map { it.value3() }.toSet() shouldBe setOf("USER")
        val correction = json.readTree(entries[6].value2().data()).single()
        listOf("field", "before", "after").map { correction[it].asString() } shouldContainExactly
            listOf("declineReason", "SALARY", "OTHER_OFFER")
        entries.joinToString { it.value2().data() }.let {
            it shouldNotContain "Salary too low"
            it shouldNotContain "Sent CV"
        }
        dsl
            .select(CHANGELOG_ENTRY.ENTITY_TYPE, CHANGELOG_ENTRY.ACTOR_KIND)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_ID.eq(applied.toString()))
            .fetch()
            .map { it.value1() to it.value2() } shouldContainExactly listOf("description_snapshot" to "USER")
    }

    @Test
    fun `without a session the status calls are 401, without the CSRF token the move is 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        val someId = UUID.randomUUID()
        val body = """{"status":"APPLIED","basedOnVersion":0}"""

        anonymous.get("/api/applications/$someId/status-history").response.status shouldBe 401
        anonymous.put("/api/applications/$someId/status", body).response.status shouldBe 401
        browser
            .exchange(HttpMethod.PUT, "/api/applications/$someId/status", body, null)
            .response.status shouldBe 403
        browser.get("/api/applications/$someId/status-history").response.status shouldBe 404
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

    /** A snapshot captured a moment ago (#86 records them through its API). */
    private fun snapshot(
        source: UUID,
        text: String,
    ): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            "insert into application_description_snapshot " +
                "(id, source_id, description, content_hash, reason, captured_at) values " +
                "(?, ?, ?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), 'MANUAL', " +
                "clock_timestamp() - interval '1 second')",
            id,
            source,
            text,
            text,
        )
        return id
    }

    private fun frozen(snapshot: UUID) =
        dsl
            .select(APPLICATION_DESCRIPTION_SNAPSHOT.FROZEN_AT)
            .from(APPLICATION_DESCRIPTION_SNAPSHOT)
            .where(APPLICATION_DESCRIPTION_SNAPSHOT.ID.eq(snapshot))
            .fetchSingle()
            .value1()

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger(40)
    }
}
