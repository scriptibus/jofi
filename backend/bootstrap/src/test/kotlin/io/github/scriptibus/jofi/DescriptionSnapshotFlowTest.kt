// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
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
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The job description history behind the real filter chain and database (#86, ADR-0046): record a text, the
 * same text again (no new version), a changed one; list the versions, read one, diff them; the changelog names
 * the snapshots with the user as actor and never their text. No session is 401, no CSRF token 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class DescriptionSnapshotFlowTest(
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

    @Test
    fun `record, record the same text again, record a change, list, read and diff the versions`() {
        val browser = owner()
        val company = browser.post("/api/companies", """{"name":"ACME GmbH"}""").ok(201)["id"].asString()
        val id =
            browser
                .post(
                    "/api/applications",
                    """{"title":"Backend Engineer","companyId":"$company"}""",
                ).ok(201)["id"]
                .asString()
        val source = browser.source(id)
        val snapshots = "/api/applications/$id/sources/$source/snapshots"

        val first = browser.post(snapshots, """{"description":"Kotlin\r\nBerlin"}""").ok()
        val again = browser.post(snapshots, """{"description":"Kotlin\nBerlin\n"}""").ok()
        val changed = browser.post(snapshots, """{"description":"Kotlin\nHamburg"}""").ok()

        listOf(first, again, changed).map { it["added"].asBoolean() } shouldContainExactly listOf(true, false, true)
        again["snapshot"]["id"] shouldBe first["snapshot"]["id"]
        val old = first["snapshot"]["id"].asString()
        val new = changed["snapshot"]["id"].asString()
        val versions = browser.get(snapshots).ok()["snapshots"]
        (0 until versions.size()).map { versions[it]["id"].asString() } shouldContainExactly listOf(old, new)
        browser.get("/api/applications/$id/snapshots/$old").ok()["description"].asString() shouldBe "Kotlin\nBerlin"
        val diff = browser.get("/api/applications/$id/description-diff?from=$old&to=$new").ok()["segments"]
        (0 until diff.size()).map {
            diff[it]["operation"].asString() to diff[it]["text"].asString()
        } shouldContainExactly
            listOf("UNCHANGED" to "Kotlin\n", "REMOVED" to "Berlin", "ADDED" to "Hamburg")
        changelogOf(old, new)
        browser.get("/api/applications/$id/snapshots/${UUID.randomUUID()}").response.status shouldBe 404
    }

    /** Adds a source through the API (#96), as a pasted text without a description yet. */
    private fun Browser.source(application: String): String =
        post("/api/applications/$application/sources", """{"kind":"MANUAL_CHAT"}""").ok(201)["id"].asString()

    /** One entry per stored version, by the user, naming source and hash but never the text. */
    private fun changelogOf(vararg snapshots: String) {
        val entries =
            dsl
                .select(CHANGELOG_ENTRY.ENTITY_TYPE, CHANGELOG_ENTRY.ACTOR_KIND, CHANGELOG_ENTRY.FIELD_CHANGES)
                .from(CHANGELOG_ENTRY)
                .where(CHANGELOG_ENTRY.ENTITY_ID.`in`(snapshots.toList()))
                .orderBy(CHANGELOG_ENTRY.ID)
                .fetch()
        entries.map { it.value1() to it.value2() } shouldContainExactly
            List(snapshots.size) { "description_snapshot" to "USER" }
        entries.joinToString { it.value3().data() }.let {
            it shouldNotContain "Berlin"
            it shouldNotContain "Hamburg"
        }
    }

    @Test
    fun `without a session the description calls are 401, without the CSRF token recording is 403`() {
        val browser = owner()
        val anonymous = Browser(mvc, "192.0.2.${addresses.incrementAndGet()}").open()
        val someId = UUID.randomUUID()
        val snapshots = "/api/applications/$someId/sources/$someId/snapshots"
        val body = """{"description":"Kotlin"}"""

        anonymous.post(snapshots, body).response.status shouldBe 401
        anonymous.get(snapshots).response.status shouldBe 401
        anonymous.get("/api/applications/$someId/snapshots/$someId").response.status shouldBe 401
        anonymous.get("/api/applications/$someId/description-diff?from=$someId&to=$someId").response.status shouldBe 401
        browser.exchange(HttpMethod.POST, snapshots, body, null).response.status shouldBe 403
        browser.post(snapshots, body).response.status shouldBe 404
    }

    private fun MvcTestResult.ok(status: Int = 200): JsonNode =
        also { response.status shouldBe status }.let { json.readTree(it.response.contentAsString) }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger(60)
    }
}
