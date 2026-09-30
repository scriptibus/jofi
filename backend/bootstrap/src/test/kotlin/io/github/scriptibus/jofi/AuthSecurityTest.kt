// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.system.config.SecurityConfiguration
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.session.jdbc.JdbcIndexedSessionRepository
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * The security filter chain end to end (threat model T5): first run, login, logout, CSRF from the
 * cookie as the SPA sends it, cookie flags, session fixation, backoff, sessions in PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class AuthSecurityTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val changelog: ChangelogPort,
    @param:Autowired private val dataSource: DataSource,
    @param:Autowired @param:Qualifier("requestMappingHandlerMapping")
    private val mappings: RequestMappingHandlerMapping,
) {
    @BeforeEach
    fun startWithoutUser() {
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
    }

    private fun browser(https: Boolean = false) =
        Browser(mvc, "198.51.100.${addresses.incrementAndGet()}", https).open()

    private fun loggedIn(): Browser =
        browser().also {
            it.post("/api/auth/first-run", """{"password":"$PASSWORD"}""").response.status shouldBe 204
        }

    @Test
    fun `without a session the API answers 401 as problem details`() {
        val result = browser().get("/api/system/info")

        result.response.status shouldBe 401
        result.response.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
        result.response.contentAsString shouldContain "urn:jofi:problem:system:not-logged-in"
    }

    @Test
    fun `every API endpoint except login, first run and the session status needs a session`() {
        val anonymous = browser()
        val open = mutableListOf<Pair<HttpMethod, String>>()

        apiEndpoints().forEach { (method, path) ->
            val status = anonymous.exchange(method, path.replace(Regex("\\{[^}]+}"), "x"), "{}").response.status
            if (status != HttpStatus.UNAUTHORIZED.value()) open += method to path
        }

        open shouldContainExactlyInAnyOrder SecurityConfiguration.PUBLIC_API
        apiEndpoints() shouldContain (HttpMethod.GET to "/api/system/info")
    }

    @Test
    fun `health stays open for the container healthcheck, other actuator endpoints are closed`() {
        browser().get("/actuator/health").response.status shouldBe 200
        browser().get("/actuator/env").response.status shouldNotBe 200
    }

    @Test
    fun `first run, login and logout work with the CSRF cookie echoed in a header`() {
        val browser = browser()
        browser.cookies.keys shouldContain Browser.CSRF_COOKIE

        browser.post("/api/auth/first-run", """{"password":"$PASSWORD"}""").response.status shouldBe 204
        browser.get("/api/system/info").response.status shouldBe 200
        browser.get("/api/auth/session").response.contentAsString shouldBe
            """{"setUp":true,"authenticated":true,"setupTokenRequired":false}"""

        browser.post("/api/auth/logout").response.status shouldBe 204
        browser.get("/api/system/info").response.status shouldBe 401

        browser.post("/api/auth/login", """{"password":"wrong password, sorry"}""").response.status shouldBe 401
        browser.post("/api/auth/login", """{"password":"$PASSWORD"}""").response.status shouldBe 204
        browser.get("/api/system/info").response.status shouldBe 200
    }

    @Test
    fun `first run is recorded as the user's change and is gone afterwards`() {
        val browser = loggedIn()

        val entries = changelog.listByEntity(UserAccount.ENTITY, ChangelogLimit(1)) as ChangelogResult.Success
        entries.value.single().actor shouldBe Actor.User
        browser.post("/api/auth/first-run", """{"password":"$PASSWORD"}""").response.status shouldBe 404
        browser().post("/api/auth/first-run", """{"password":"$PASSWORD"}""").response.status shouldBe 404
    }

    @Test
    fun `unsafe requests without the CSRF token are refused, also before login`() {
        val browser = browser()

        val refused = browser.post("/api/auth/first-run", """{"password":"$PASSWORD"}""", csrf = null)

        refused.response.status shouldBe 403
        refused.response.contentAsString shouldContain "urn:jofi:problem:system:csrf"
        dsl.fetchCount(USER_ACCOUNT) shouldBe 0

        val session = loggedIn()
        session.post("/api/auth/logout", csrf = null).response.status shouldBe 403
        session.post("/api/auth/logout", csrf = "forged").response.status shouldBe 403
        session.get("/api/system/info").response.status shouldBe 200
    }

    @Test
    fun `the session cookie is HttpOnly and SameSite, the CSRF cookie readable by the SPA`() {
        val browser = browser()
        val response = browser.post("/api/auth/first-run", """{"password":"$PASSWORD"}""").response

        val session = browser.lastSetCookies.single { it.startsWith("${Browser.SESSION_COOKIE}=") }
        session shouldContain "HttpOnly"
        session shouldContain "SameSite=Lax"
        session shouldNotContain "Secure"
        // Written as a servlet cookie with a SameSite attribute (the mock's header omits attributes).
        val csrf = response.cookies.last { it.name == Browser.CSRF_COOKIE }
        csrf.isHttpOnly shouldBe false
        csrf.secure shouldBe false
        csrf.getAttribute("SameSite") shouldBe "Lax"
    }

    @Test
    fun `behind HTTPS both cookies are Secure`() {
        loggedIn()
        val browser = browser(https = true)

        val response = browser.post("/api/auth/login", """{"password":"$PASSWORD"}""").response
        response.status shouldBe 204

        browser.lastSetCookies.single { it.startsWith("${Browser.SESSION_COOKIE}=") } shouldContain "Secure"
        response.cookies.last { it.name == Browser.CSRF_COOKIE }.secure shouldBe true
    }

    @Test
    fun `logging in again replaces the session id, so a planted session is worthless`() {
        val browser = loggedIn()
        val before = browser.cookies.getValue(Browser.SESSION_COOKIE)

        browser.post("/api/auth/login", """{"password":"$PASSWORD"}""").response.status shouldBe 204

        val after = browser.cookies.getValue(Browser.SESSION_COOKIE)
        after shouldNotBe before
        val attacker = Browser(mvc, "203.0.113.9").also { it.cookies[Browser.SESSION_COOKIE] = before }
        attacker.get("/api/system/info").response.status shouldBe 401
        browser.get("/api/system/info").response.status shouldBe 200
    }

    @Test
    fun `repeated wrong passwords back off per client, other clients still get in`() {
        loggedIn()
        val guesser = browser()

        repeat(6) {
            guesser.post("/api/auth/login", """{"password":"guess number $it"}""").response.status shouldBe 401
        }
        val throttled = guesser.post("/api/auth/login", """{"password":"$PASSWORD"}""")

        throttled.response.status shouldBe 429
        throttled.response.getHeader("Retry-After").shouldNotBeNull()
        throttled.response.contentAsString shouldContain "urn:jofi:problem:system:login-throttled"
        browser().post("/api/auth/login", """{"password":"$PASSWORD"}""").response.status shouldBe 204
    }

    @Test
    fun `sessions live in PostgreSQL, so they survive a restart`() {
        val browser = loggedIn()
        val sessionId = browser.sessionId.shouldNotBeNull()

        // A new repository on the same database stands in for a restarted app.
        val afterRestart: FindByIndexNameSessionRepository<out Session> =
            JdbcIndexedSessionRepository(JdbcTemplate(dataSource), TransactionTemplate(transactionManager()))
        val stored: Session = afterRestart.findById(sessionId).shouldNotBeNull()

        stored.getAttribute<Any>("SPRING_SECURITY_CONTEXT").shouldNotBeNull()
        afterRestart.findByPrincipalName(UserAccount.PRINCIPAL).keys shouldContain sessionId
    }

    @Test
    fun `changing the password ends every other session`() {
        val phone = loggedIn()
        val laptop = browser().also { it.post("/api/auth/login", """{"password":"$PASSWORD"}""") }
        laptop.get("/api/system/info").response.status shouldBe 200

        phone
            .put("/api/auth/password", """{"currentPassword":"$PASSWORD","newPassword":"$NEW_PASSWORD"}""")
            .response.status shouldBe 204

        phone.get("/api/system/info").response.status shouldBe 200
        laptop.get("/api/system/info").response.status shouldBe 401
        browser().post("/api/auth/login", """{"password":"$PASSWORD"}""").response.status shouldBe 401
        browser().post("/api/auth/login", """{"password":"$NEW_PASSWORD"}""").response.status shouldBe 204
    }

    private fun transactionManager() = JdbcTransactionManager(dataSource)

    private fun apiEndpoints(): List<Pair<HttpMethod, String>> =
        mappings.handlerMethods.keys.flatMap { info ->
            val paths =
                info.pathPatternsCondition
                    ?.patternValues
                    .orEmpty()
                    .filter { it.startsWith("/api/") }
            val methods =
                info.methodsCondition.methods
                    .map {
                        HttpMethod.valueOf(
                            it.name,
                        )
                    }.ifEmpty { listOf(HttpMethod.GET) }
            paths.flatMap { path -> methods.map { it to path } }
        }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val NEW_PASSWORD = "an even better passphrase"
        val addresses = AtomicInteger()
    }
}
