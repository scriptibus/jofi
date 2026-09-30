// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.application.GetSessionAccountUseCase
import io.github.scriptibus.jofi.system.domain.AccountId
import io.github.scriptibus.jofi.system.domain.AccountLookup
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SessionValidityFilterTest {
    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private val account = AccountId(UUID.fromString("00000000-0000-0000-0000-0000000000ac"))
    private val sessionAccount =
        mockk<GetSessionAccountUseCase> {
            every { execute() } returns
                AccountLookup.Found(account)
        }
    private val filter =
        SessionValidityFilter(
            sessionAccount,
            Duration.ofDays(30),
            Clock.fixed(now, ZoneOffset.UTC),
            SecurityProblemHandler(JsonMapper.builder().build()),
        )

    private fun session(
        loggedInAt: Instant? = now.minus(Duration.ofDays(1)),
        accountId: AccountId = account,
    ) = MockHttpSession().apply {
        setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextImpl())
        setAttribute(SessionSecurity.ACCOUNT_ATTRIBUTE, accountId.value.toString())
        loggedInAt?.let { setAttribute(SessionSecurity.LOGIN_TIME_ATTRIBUTE, it.toEpochMilli()) }
    }

    private fun run(session: MockHttpSession): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest("GET", "/api/system/info").apply { setSession(session) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }

    @Test
    fun `a session of the current account within its lifetime continues`() {
        val session = session()

        val (_, chain) = run(session)

        chain.request.shouldNotBeNull()
        session.isInvalid shouldBe false
    }

    @Test
    fun `the lifetime counts from login, not from the session's creation`() {
        val session = session(loggedInAt = now.minus(Duration.ofDays(31)))

        val (_, chain) = run(session)

        session.isInvalid shouldBe true
        chain.request.shouldNotBeNull()
    }

    @Test
    fun `a session without a login time or of another account ends`() {
        listOf(session(loggedInAt = null), session(accountId = AccountId(UUID.randomUUID()))).forEach {
            run(it)
            it.isInvalid shouldBe true
        }
        every { sessionAccount.execute() } returns AccountLookup.None
        session().also { run(it) }.isInvalid shouldBe true
    }

    @Test
    fun `when the account cannot be read the request is refused but the session stays`() {
        every { sessionAccount.execute() } returns AccountLookup.StorageFailure
        val session = session()

        val (response, chain) = run(session)

        response.status shouldBe 503
        response.contentType shouldBe "application/problem+json"
        response.contentAsString shouldContain AuthProblems.UNAVAILABLE
        chain.request.shouldBeNull()
        session.isInvalid shouldBe false
    }
}
