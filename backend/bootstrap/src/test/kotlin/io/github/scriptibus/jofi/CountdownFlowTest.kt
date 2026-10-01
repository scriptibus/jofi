// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COUNTDOWN
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Countdowns behind the real filter chain and database (#112): a custom countdown is created, listed, edited and
 * deleted in two steps, each recorded with the user as actor and without the title; the dashboard query combines it
 * with the next interview, an application deadline and an offer answer deadline read from the applications context,
 * soonest first in the viewer's zone. No session is 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class CountdownFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()
    private val today: LocalDate = LocalDate.now(BERLIN)

    @BeforeEach
    fun startWithoutUserOrCountdowns() {
        dsl.deleteFrom(COUNTDOWN).execute()
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
    fun `create, list, edit and delete a custom countdown with confirmation, then query the dashboard`() {
        val browser = owner()
        val request = """{"title":"Notice ends at Erika's firm","targetDate":"${today.plusDays(3)}"}"""
        val id = browser.post("/api/countdowns", request).ok(201)["id"].asString()
        browser
            .get("/api/countdowns")
            .ok()["countdowns"]
            .toList()
            .map { it["id"].asString() } shouldBe listOf(id)

        moveToNextWeek(browser, id)

        val subjects = seedApplications(browser)
        dashboard(browser) shouldContainExactly
            listOf(
                "OFFER_ANSWER_DEADLINE" to subjects.offer,
                "CUSTOM" to id,
                "APPLICATION_DEADLINE" to subjects.deadline,
                "NEXT_INTERVIEW" to subjects.interview,
            )

        deleteWithConfirmation(browser, id)
        dashboard(browser).map { it.first } shouldBe
            listOf("OFFER_ANSWER_DEADLINE", "APPLICATION_DEADLINE", "NEXT_INTERVIEW")

        recordedByUserWithoutTitle(id)
    }

    @Test
    fun `an unknown zone is a 400 and no session is a 401`() {
        val browser = owner()

        val refused = browser.get("/api/dashboard/countdowns?timeZone=Mars/Olympus")
        refused.response.status shouldBe 400
        refused.body()["violations"].toString() shouldBe """[{"field":"timeZone","problem":"INVALID_TIME_ZONE"}]"""

        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        anonymous.get("/api/countdowns").response.status shouldBe 401
        anonymous.get("/api/dashboard/countdowns?timeZone=UTC").response.status shouldBe 401
    }

    /** Moves the countdown to a week from today; repeating it on the old version is a 409. */
    private fun moveToNextWeek(
        browser: Browser,
        id: String,
    ) {
        val moved = """{"title":"Notice ends at Erika's firm","targetDate":"${today.plusDays(7)}"}"""
        val edited = browser.put("/api/countdowns/$id", """{"details":$moved,"basedOnVersion":0}""").ok()
        edited["version"].asInt() shouldBe 1
        browser.put("/api/countdowns/$id", """{"details":$moved,"basedOnVersion":0}""").response.status shouldBe 409
    }

    /** Created, edited and deleted, each by the user; neither field changes nor descriptions quote the title. */
    private fun recordedByUserWithoutTitle(id: String) {
        val entries =
            dsl
                .selectFrom(CHANGELOG_ENTRY)
                .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq("countdown"))
                .and(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
                .orderBy(CHANGELOG_ENTRY.ID)
                .fetch()
        entries.map { it.actorKind } shouldContainExactly List(3) { "USER" }
        entries.map { it.description } shouldContainExactly
            listOf("Created countdown", "Edited countdown", "Deleted countdown")
        entries.forEach {
            it.fieldChanges.data() shouldNotContain "Erika"
            it.description shouldNotContain "Erika"
        }
    }

    /** The first call asks, naming the countdown; the repeat with the token deletes it. */
    private fun deleteWithConfirmation(
        browser: Browser,
        id: String,
    ) {
        val first = browser.delete("/api/countdowns/$id")
        first.response.status shouldBe 428
        first.body()["effect"]["name"].asString() shouldBe "Notice ends at Erika's firm"
        val token = first.body()["confirmationToken"].asString()
        browser.delete("/api/countdowns/$id", mapOf(Confirmations.HEADER to token)).response.status shouldBe 204
        browser.get("/api/countdowns").ok()["countdowns"].isEmpty() shouldBe true
    }

    private data class Subjects(
        val offer: String,
        val deadline: String,
        val interview: String,
    )

    /**
     * An application with a deadline in ten days, one whose deadline passed, an offer to answer in five days and an
     * interview far ahead; only the first, the offer and the interview show.
     */
    private fun seedApplications(browser: Browser): Subjects {
        val company = browser.post("/api/companies", """{"name":"ACME GmbH"}""").ok(201)["id"].asString()

        fun application(more: String): String =
            browser
                .post("/api/applications", """{"title":"Engineer","companyId":"$company"$more}""")
                .ok(201)["id"]
                .asString()
        val deadline = application(""","deadline":"${today.plusDays(10)}"""")
        application(""","deadline":"${today.minusDays(1)}"""")
        val offer = application(""","offer":{"answerBy":"${today.plusDays(5)}"}""")
        browser.put("/api/applications/$offer/status", """{"status":"OFFER","basedOnVersion":0}""").ok()
        val interview =
            browser
                .post(
                    "/api/applications/$deadline/interviews",
                    """{"type":"HR","localStart":"2099-01-05T10:00","timeZone":"Europe/Berlin"}""",
                ).ok(201)["id"]
                .asString()
        return Subjects(offer, deadline, interview)
    }

    /** The dashboard in Berlin as (source, subject id) pairs, in its order. */
    private fun dashboard(browser: Browser): List<Pair<String, String>> =
        browser
            .get("/api/dashboard/countdowns?timeZone=Europe/Berlin")
            .ok()["countdowns"]
            .toList()
            .map { it["source"].asString() to it["subjectId"].asString() }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        val addresses = AtomicInteger(130)
    }
}
