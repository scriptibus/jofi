// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.application.ChangePasswordUseCase
import io.github.scriptibus.jofi.system.application.CompleteFirstRunUseCase
import io.github.scriptibus.jofi.system.application.GetAuthStatusUseCase
import io.github.scriptibus.jofi.system.application.LogInUseCase
import io.github.scriptibus.jofi.system.domain.AccountId
import io.github.scriptibus.jofi.system.domain.AuthStatus
import io.github.scriptibus.jofi.system.domain.AuthStatusResult
import io.github.scriptibus.jofi.system.domain.FirstRunResult
import io.github.scriptibus.jofi.system.domain.LoginResult
import io.github.scriptibus.jofi.system.domain.PasswordChangeRequest
import io.github.scriptibus.jofi.system.domain.PasswordChangeResult
import io.github.scriptibus.jofi.system.domain.PasswordPolicyCheck
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Duration
import java.util.UUID

/**
 * How the auth endpoints map use-case results to HTTP. The security filter chain itself (401, CSRF,
 * session fixation, cookies) is tested end to end in bootstrap (`AuthSecurityTest`). Problem details are
 * switched on as in production (`application.yaml` in bootstrap).
 */
@WebMvcTest(AuthController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthControllerTest.UseCases::class)
class AuthControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val logIn: LogInUseCase,
    @param:Autowired private val firstRun: CompleteFirstRunUseCase,
    @param:Autowired private val changePassword: ChangePasswordUseCase,
    @param:Autowired private val status: GetAuthStatusUseCase,
) {
    @TestConfiguration
    class UseCases {
        @Bean
        fun logIn(): LogInUseCase = mockk()

        @Bean
        fun firstRun(): CompleteFirstRunUseCase = mockk()

        @Bean
        fun changePassword(): ChangePasswordUseCase = mockk()

        @Bean
        fun status(): GetAuthStatusUseCase = mockk()
    }

    @BeforeEach
    fun reset() = clearMocks(logIn, firstRun, changePassword, status)

    private fun post(
        path: String,
        json: String,
    ) = mvc
        .post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)

    @Test
    fun `a password change without a session is refused`() {
        mvc
            .put()
            .uri("/api/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"currentPassword":"x","newPassword":"y"}""")
            .assertThat()
            .hasStatus(401)
    }

    @Test
    fun `a right password starts a session holding the owner`() {
        every { logIn.execute("pw", ThrottleKey.Client("127.0.0.1")) } returns LoginResult.LoggedIn(ACCOUNT)

        val result = post("/api/auth/login", """{"password":"pw"}""").exchange()

        result.response.status shouldBe HttpStatus.NO_CONTENT.value()
        result.request.session
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
            .shouldNotBeNull()
        result.request.session
            .getAttribute(SessionSecurity.ACCOUNT_ATTRIBUTE) shouldBe ACCOUNT.value.toString()
    }

    @Test
    fun `login failures are distinguishable problem details`() {
        val cases =
            mapOf(
                LoginResult.InvalidCredentials to (401 to AuthProblems.INVALID_CREDENTIALS),
                LoginResult.NotSetUp to (409 to AuthProblems.NOT_SET_UP),
                LoginResult.Throttled(Duration.ofMillis(2100)) to (429 to AuthProblems.THROTTLED),
                LoginResult.StorageFailure to (503 to AuthProblems.UNAVAILABLE),
            )
        cases.forEach { (result, expected) ->
            every { logIn.execute(any(), any()) } returns result

            val response = post("/api/auth/login", """{"password":"pw"}""").assertThat()

            response.hasStatus(expected.first).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
            response.bodyJson().extractingPath("$.type").isEqualTo(expected.second)
        }
    }

    @Test
    fun `a throttled attempt says when to retry`() {
        every { logIn.execute(any(), any()) } returns LoginResult.Throttled(Duration.ofMillis(2100))

        post(
            "/api/auth/login",
            """{"password":"pw"}""",
        ).assertThat().hasStatus(429).headers().hasValue("Retry-After", "3")
    }

    @Test
    fun `first run maps every outcome`() {
        val cases =
            mapOf(
                FirstRunResult.AlreadySetUp to (404 to AuthProblems.ALREADY_SET_UP),
                FirstRunResult.InvalidSetupToken to (403 to AuthProblems.INVALID_SETUP_TOKEN),
                FirstRunResult.WeakPassword(PasswordPolicyCheck.TooShort(15)) to (422 to AuthProblems.WEAK_PASSWORD),
                FirstRunResult.WeakPassword(PasswordPolicyCheck.TooLong(256)) to (422 to AuthProblems.WEAK_PASSWORD),
                FirstRunResult.Throttled(Duration.ofSeconds(1)) to (429 to AuthProblems.THROTTLED),
                FirstRunResult.StorageFailure to (503 to AuthProblems.UNAVAILABLE),
            )
        cases.forEach { (result, expected) ->
            every { firstRun.execute(any(), any(), any()) } returns result

            post("/api/auth/first-run", """{"password":"pw"}""")
                .assertThat()
                .hasStatus(expected.first)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo(expected.second)
        }
    }

    @Test
    fun `completed first run starts a session and passes the setup token on`() {
        every { firstRun.execute("a long enough password", "tok", any()) } returns FirstRunResult.Completed(ACCOUNT)

        val result =
            post(
                "/api/auth/first-run",
                """{"password":"a long enough password","setupToken":"tok"}""",
            ).exchange()

        result.response.status shouldBe 204
        result.request.session
            .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
            .shouldNotBeNull()
    }

    private fun putPassword(session: MockHttpSession) =
        mvc
            .put()
            .uri("/api/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"currentPassword":"old","newPassword":"new"}""")
            .session(session)
            .assertThat()

    @Test
    fun `a password change keeps the asking session`() {
        val session = MockHttpSession(null, "session-42")
        val request = slot<PasswordChangeRequest>()
        every { changePassword.execute(capture(request)) } returns PasswordChangeResult.Changed

        putPassword(session).hasStatus(204)

        request.captured.session.value shouldBe "session-42"
        request.captured.currentPassword shouldBe "old"
    }

    @Test
    fun `password change failures are problem details, surviving sessions included`() {
        val session = MockHttpSession(null, "session-42")
        val cases =
            mapOf(
                PasswordChangeResult.ChangedButOtherSessionsRemain to (500 to AuthProblems.OTHER_SESSIONS_REMAIN),
                PasswordChangeResult.WrongCurrentPassword to (403 to AuthProblems.INVALID_CREDENTIALS),
                PasswordChangeResult.NotSetUp to (409 to AuthProblems.NOT_SET_UP),
                PasswordChangeResult.WeakPassword(PasswordPolicyCheck.TooShort(15)) to
                    (422 to AuthProblems.WEAK_PASSWORD),
            )
        cases.forEach { (result, expected) ->
            every { changePassword.execute(any()) } returns result

            val response = putPassword(session).hasStatus(expected.first)
            response.bodyJson().extractingPath("$.type").isEqualTo(expected.second)
        }
    }

    @Test
    fun `the session status reports setup and login state`() {
        every { status.execute() } returns
            AuthStatusResult.Success(AuthStatus(setUp = false, setupTokenRequired = true))

        mvc
            .get()
            .uri("/api/auth/session")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"setUp":false,"authenticated":false,"setupTokenRequired":true}""")
    }

    @Test
    fun `logout answers no content`() {
        mvc
            .post()
            .uri("/api/auth/logout")
            .assertThat()
            .hasStatus(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `request bodies never print passwords or tokens`() {
        val printed = "${LoginRequest("pw1")} ${FirstRunRequest("pw2", "tok")} ${ChangePasswordRequest("pw3", "pw4")}"

        listOf("pw1", "pw2", "tok", "pw3", "pw4").forEach { (it in printed) shouldBe false }
    }

    private companion object {
        val ACCOUNT = AccountId(UUID.fromString("00000000-0000-0000-0000-0000000000ac"))
    }
}
