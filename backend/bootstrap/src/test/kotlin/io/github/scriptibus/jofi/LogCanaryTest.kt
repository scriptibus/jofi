// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * With verbose framework logging, no password, setup token, session id, CSRF token, secret or key
 * material reaches the logs (threat model T4).
 */
@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(
    properties = [
        "logging.level.org.springframework.security=DEBUG",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.org.springframework.session=DEBUG",
        "logging.level.org.jooq=DEBUG",
        "logging.level.io.github.scriptibus=DEBUG",
    ],
)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class LogCanaryTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val setupToken: SetupTokenPort,
    @param:Autowired private val secrets: SecretStorePort,
    @param:Value("\${jofi.data-dir}") private val dataDirectory: Path,
) {
    @BeforeEach
    fun freshInstance() {
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        setupToken.issue()
    }

    @Test
    fun `no password, token, session id, secret or key material reaches the logs`(output: CapturedOutput) {
        val token = SetupTokens.read()
        val browser = Browser(mvc, "192.0.2.51").open()
        browser.post("/api/auth/first-run", """{"password":"$PASSWORD","setupToken":"$token"}""")
        browser.post("/api/auth/login", """{"password":"$WRONG_PASSWORD"}""")
        browser.post("/api/auth/login", """{"password":"$PASSWORD"}""")
        browser.put("/api/auth/password", """{"currentPassword":"$PASSWORD","newPassword":"$NEW_PASSWORD"}""")
        val sessionId = browser.sessionId.shouldNotBeNull()
        val csrfToken = browser.cookies.getValue(Browser.CSRF_COOKIE)
        browser.get("/api/system/info").response.status shouldBe 200
        val id = SecretId(UUID.randomUUID())
        secrets.put(id, SecretValue(API_KEY))
        secrets.get(id) shouldBe SecretResult.Success(SecretValue(API_KEY))

        // The capture works and the verbose loggers ran: the request bodies are logged, redacted.
        output.all shouldContain "Login failed: wrong password"
        output.all shouldContain "LoginRequest(password=***)"
        listOf(PASSWORD, WRONG_PASSWORD, NEW_PASSWORD, token, API_KEY, sessionId, csrfToken).forEach {
            output.all shouldNotContain it
        }
        keyMaterial().shouldNotBeEmpty().forEach { output.all shouldNotContain it }
        output.all shouldNotContain "generated security password"
    }

    private fun keyMaterial(): List<String> {
        val keyset = Files.readString(dataDirectory.resolve("secrets/master-keyset.json"))
        return Regex("\"value\"\\s*:\\s*\"([^\"]+)\"").findAll(keyset).map { it.groupValues[1] }.toList()
    }

    private companion object {
        const val PASSWORD = "log canary password 1"
        const val WRONG_PASSWORD = "log canary wrong guess"
        const val NEW_PASSWORD = "log canary password 2"
        const val API_KEY = "sk-log-canary-api-key"
    }
}
