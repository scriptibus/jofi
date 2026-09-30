// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.AccountLookup
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.AuthStatus
import io.github.scriptibus.jofi.system.domain.AuthStatusResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class AuthStatusUseCasesTest {
    private val users = FakeUsers()
    private val setupToken =
        mockk<SetupTokenPort> {
            every { issue() } returns AuthSideEffectResult.Success
            every { discard() } returns AuthSideEffectResult.Success
        }
    private val status = GetAuthStatusUseCase(users)
    private val prepare = PrepareFirstRunUseCase(users, setupToken)
    private val sessionAccount = GetSessionAccountUseCase(users)

    @Test
    fun `before first run the status always asks for the setup token`() {
        status.execute() shouldBe AuthStatusResult.Success(AuthStatus(setUp = false, setupTokenRequired = true))
    }

    @Test
    fun `after first run no token is required`() {
        users.account = AuthFixtures.account()

        status.execute() shouldBe AuthStatusResult.Success(AuthStatus(setUp = true, setupTokenRequired = false))
    }

    @Test
    fun `startup issues the token while no password exists`() {
        prepare.execute() shouldBe AuthSideEffectResult.Success

        verify(exactly = 1) { setupToken.issue() }
    }

    @Test
    fun `startup removes a leftover token once a password exists`() {
        users.account = AuthFixtures.account()

        prepare.execute() shouldBe AuthSideEffectResult.Success
        verify { setupToken.discard() }
        verify(exactly = 0) { setupToken.issue() }
    }

    @Test
    fun `sessions are checked against the current account`() {
        sessionAccount.execute() shouldBe AccountLookup.None

        users.account = AuthFixtures.account()
        sessionAccount.execute() shouldBe AccountLookup.Found(AuthFixtures.ACCOUNT_ID)

        users.failing = true
        sessionAccount.execute() shouldBe AccountLookup.StorageFailure
    }

    @Test
    fun `storage failures are reported`() {
        users.failing = true

        status.execute() shouldBe AuthStatusResult.StorageFailure
        prepare.execute() shouldBe AuthSideEffectResult.Failure
    }
}
