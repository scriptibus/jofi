// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.application.port.UserSessionsPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.PasswordResetResult
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ResetPasswordUseCaseTest {
    private val users = FakeUsers(AuthFixtures.account())
    private val changelog = FakeChangelog()
    private val sessions =
        mockk<UserSessionsPort> { every { endAll() } returns AuthSideEffectResult.Success }
    private val setupToken = mockk<SetupTokenPort> { every { issue() } returns AuthSideEffectResult.Success }
    private val useCase =
        ResetPasswordUseCase(
            users,
            sessions,
            setupToken,
            changelog,
            FakeTransactions(users, changelog),
            AuthFixtures.clock,
        )

    @Test
    fun `a reset deletes the account, ends every session, issues a token and is recorded`() {
        useCase.execute() shouldBe PasswordResetResult.Reset

        users.account.shouldBeNull()
        verify { sessions.endAll() }
        verify { setupToken.issue() }
        changelog.entries.single().let {
            it.entity shouldBe UserAccount.ENTITY
            it.actor shouldBe Actor.System("password-reset")
        }
    }

    @Test
    fun `without an account there is nothing to reset`() {
        users.account = null

        useCase.execute() shouldBe PasswordResetResult.NothingToReset
        verify(exactly = 0) { sessions.endAll() }
    }

    @Test
    fun `failures after the deletion are reported`() {
        every { sessions.endAll() } returns AuthSideEffectResult.Failure

        useCase.execute() shouldBe PasswordResetResult.ResetWithFailures
        users.account.shouldBeNull()
    }

    @Test
    fun `without its changelog entry the account stays`() {
        changelog.failing = true

        useCase.execute() shouldBe PasswordResetResult.StorageFailure
        users.account shouldBe AuthFixtures.account()
        changelog.entries.shouldBeEmpty()
    }

    @Test
    fun `a storage failure is reported`() {
        users.failing = true

        useCase.execute() shouldBe PasswordResetResult.StorageFailure
    }
}
