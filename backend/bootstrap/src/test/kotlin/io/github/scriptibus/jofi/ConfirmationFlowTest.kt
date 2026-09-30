// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The REST convention for two-step confirmations (ADR-0039) behind the real security filter chain:
 * 428 with a token first, the operation runs only when the same session repeats the call with it,
 * and session, CSRF and the single use of the token all hold.
 */
@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(
    properties = [
        // Verbose logging, as in LogCanaryTest: tokens must not reach the logs even then.
        "logging.level.org.springframework.security=DEBUG",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.io.github.scriptibus=DEBUG",
    ],
)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class, ConfirmationFlowTest.ProbeController::class)
class ConfirmationFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val probes: ProbeController,
) {
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun startWithoutUser() {
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
        probes.deleted.clear()
    }

    private fun browser() = Browser(mvc, "203.0.113.${addresses.incrementAndGet()}").open()

    private fun owner(): Browser =
        browser().also {
            val body = """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}"""
            it.post("/api/auth/first-run", body).response.status shouldBe 204
        }

    private fun secondDevice(): Browser =
        browser().also { it.post("/api/auth/login", """{"password":"$PASSWORD"}""").response.status shouldBe 204 }

    private fun tokenFrom(result: MvcTestResult): String {
        result.response.status shouldBe HttpStatus.PRECONDITION_REQUIRED.value()
        result.response.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
        return json.readTree(result.response.contentAsString)["confirmationToken"].asString()
    }

    private fun Browser.confirm(
        path: String,
        token: String,
        csrf: String? = cookies[Browser.CSRF_COOKIE],
    ) = delete(path, mapOf(Confirmations.HEADER to token), csrf)

    @Test
    fun `the first call only asks, the second call with the token runs the operation`() {
        val browser = owner()

        val first = browser.delete("/api/test/probes/42")
        val token = tokenFrom(first)
        val problem = json.readTree(first.response.contentAsString)
        problem["type"].asString() shouldBe Confirmations.REQUIRED
        problem["operation"].asString() shouldBe "test.probes.delete"
        problem["targets"].toString() shouldBe """["42"]"""
        problem["expiresAt"].asString() shouldContain "T"
        probes.deleted.shouldBeEmpty()

        browser.confirm("/api/test/probes/42", token).response.status shouldBe 204
        probes.deleted shouldContainExactly listOf("42")
    }

    @Test
    fun `a used token is refused with problem details`() {
        val browser = owner()
        val token = tokenFrom(browser.delete("/api/test/probes/42"))
        browser.confirm("/api/test/probes/42", token).response.status shouldBe 204

        val replay = browser.confirm("/api/test/probes/42", token)

        replay.response.status shouldBe HttpStatus.PRECONDITION_FAILED.value()
        replay.response.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
        replay.response.contentAsString shouldContain Confirmations.INVALID
        probes.deleted shouldContainExactly listOf("42")
    }

    @Test
    fun `a token cannot be used for another target or from another session`() {
        val browser = owner()
        val other = secondDevice()

        val retargeted = browser.confirm("/api/test/probes/43", tokenFrom(browser.delete("/api/test/probes/42")))
        val stolen = other.confirm("/api/test/probes/42", tokenFrom(browser.delete("/api/test/probes/42")))

        retargeted.response.status shouldBe HttpStatus.PRECONDITION_FAILED.value()
        stolen.response.status shouldBe HttpStatus.PRECONDITION_FAILED.value()
        probes.deleted.shouldBeEmpty()
    }

    @Test
    fun `tokens never reach the logs`(output: CapturedOutput) {
        val browser = owner()
        val used = tokenFrom(browser.delete("/api/test/probes/42"))
        browser.confirm("/api/test/probes/42", used).response.status shouldBe 204
        browser.confirm("/api/test/probes/42", used).response.status shouldBe 412
        val mismatched = tokenFrom(browser.delete("/api/test/probes/42"))
        browser.confirm("/api/test/probes/43", mismatched).response.status shouldBe 412

        output.all shouldContain "Confirmation refused"
        output.all shouldNotContain used
        output.all shouldNotContain mismatched
    }

    @Test
    fun `made-up tokens are refused`() {
        val browser = owner()

        browser.confirm("/api/test/probes/42", "guessed").response.status shouldBe
            HttpStatus.PRECONDITION_FAILED.value()
        probes.deleted.shouldBeEmpty()
    }

    @Test
    fun `CSRF still applies to both steps and a refused request does not spend the token`() {
        val browser = owner()

        browser.delete("/api/test/probes/42", csrf = null).response.status shouldBe 403
        val token = tokenFrom(browser.delete("/api/test/probes/42"))
        browser.confirm("/api/test/probes/42", token, csrf = null).response.status shouldBe 403
        browser.confirm("/api/test/probes/42", token, csrf = "forged").response.status shouldBe 403
        probes.deleted.shouldBeEmpty()

        browser.confirm("/api/test/probes/42", token).response.status shouldBe 204
    }

    @Test
    fun `without a session both steps are 401`() {
        val browser = owner()
        val token = tokenFrom(browser.delete("/api/test/probes/42"))
        val anonymous = browser()

        anonymous.delete("/api/test/probes/42").response.status shouldBe 401
        anonymous.confirm("/api/test/probes/42", token).response.status shouldBe 401
        probes.deleted.shouldBeEmpty()
    }

    /**
     * The convention as a destructive endpoint uses it. In a feature the confirmation call and the
     * operation both sit in the feature's use case, so MCP tools get the same gate; the probe folds
     * them together because it has nothing to delete.
     */
    @TestComponent
    @RestController
    class ProbeController(
        private val confirmAction: ConfirmActionUseCase,
    ) {
        val deleted = CopyOnWriteArrayList<String>()

        @DeleteMapping("/api/test/probes/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        fun deleteProbe(
            @PathVariable id: String,
            @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
            request: HttpServletRequest,
        ) {
            val action = ConfirmableAction("test.probes.delete", listOf(id), "probe $id")
            val confirmationRequest =
                ConfirmationRequest(Confirmations.requester(request), action, Confirmations.token(confirmation))
            when (val result = confirmAction.execute(confirmationRequest)) {
                ConfirmationResult.Confirmed -> deleted += id
                is ConfirmationResult.Unconfirmed -> throw Confirmations.problem(result)
            }
        }
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val addresses = AtomicInteger()
    }
}
