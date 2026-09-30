// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.system.application.port.PasswordResetMarkerPort
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
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test

class ResetPasswordUseCaseTest {
    private val users = FakeUsers(AuthFixtures.account())
    private val changelog = FakeChangelog()
    private val sessions =
        mockk<UserSessionsPort> { every { endAll() } returns AuthSideEffectResult.Success }
    private val setupToken =
        mockk<SetupTokenPort> {
            every { isIssued() } returns false
            every { discard() } returns AuthSideEffectResult.Success
            every { issue() } returns AuthSideEffectResult.Success
        }
    private val marker = FakeMarker()
    private val useCase =
        ResetPasswordUseCase(
            users,
            sessions,
            setupToken,
            marker,
            changelog,
            FakeTransactions(users, changelog),
            AuthFixtures.clock,
        )

    private class FakeMarker(
        var set: Boolean = false,
    ) : PasswordResetMarkerPort {
        override fun isSet() = set

        override fun set() = AuthSideEffectResult.Success.also { set = true }

        override fun clear() = AuthSideEffectResult.Success.also { set = false }
    }

    @Test
    fun `a reset deletes the account, ends every session, replaces the token and is recorded`() {
        useCase.execute(requested = true) shouldBe PasswordResetResult.Reset

        users.account.shouldBeNull()
        verify { sessions.endAll() }
        verifyOrder {
            setupToken.discard()
            setupToken.issue()
        }
        marker.set shouldBe true
        changelog.entries.single().let {
            it.entity shouldBe UserAccount.ENTITY
            it.actor shouldBe Actor.System("password-reset")
        }
    }

    @Test
    fun `a flag left set resets once, never the password chosen afterwards`() {
        useCase.execute(requested = true) shouldBe PasswordResetResult.Reset
        users.account = AuthFixtures.account("the new password!!")

        useCase.execute(requested = true) shouldBe PasswordResetResult.AlreadyApplied

        users.account shouldBe AuthFixtures.account("the new password!!")
    }

    @Test
    fun `a pending setup token means the reset has nothing to do`() {
        every { setupToken.isIssued() } returns true

        useCase.execute(requested = true) shouldBe PasswordResetResult.AlreadyApplied
        users.account shouldBe AuthFixtures.account()
    }

    @Test
    fun `a start without the flag clears the marker, so the next flag resets again`() {
        marker.set = true

        useCase.execute(requested = false) shouldBe PasswordResetResult.NotRequested

        marker.set shouldBe false
        useCase.execute(requested = true) shouldBe PasswordResetResult.Reset
    }

    @Test
    fun `without an account there is nothing to reset`() {
        users.account = null

        useCase.execute(requested = true) shouldBe PasswordResetResult.NothingToReset
        verify(exactly = 0) { sessions.endAll() }
    }

    @Test
    fun `failures after the deletion are reported`() {
        every { sessions.endAll() } returns AuthSideEffectResult.Failure

        useCase.execute(requested = true) shouldBe PasswordResetResult.ResetWithFailures
        users.account.shouldBeNull()
    }

    @Test
    fun `without its changelog entry the account stays`() {
        changelog.failing = true

        useCase.execute(requested = true) shouldBe PasswordResetResult.StorageFailure
        users.account shouldBe AuthFixtures.account()
        changelog.entries.shouldBeEmpty()
        marker.set shouldBe false
    }

    @Test
    fun `a storage failure is reported`() {
        users.failing = true

        useCase.execute(requested = true) shouldBe PasswordResetResult.StorageFailure
    }
}
