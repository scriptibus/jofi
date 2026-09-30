// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.system.application.AuthFixtures.NOW
import io.github.scriptibus.jofi.system.application.AuthFixtures.PASSWORD
import io.github.scriptibus.jofi.system.application.AuthFixtures.client
import io.github.scriptibus.jofi.system.application.port.UserSessionsPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.PasswordChangeRequest
import io.github.scriptibus.jofi.system.domain.PasswordChangeResult
import io.github.scriptibus.jofi.system.domain.PasswordPolicyCheck
import io.github.scriptibus.jofi.system.domain.SessionRef
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration

class ChangePasswordUseCaseTest {
    private val newPassword = "an even better passphrase"
    private val session = SessionRef("this-session")
    private val users = FakeUsers(AuthFixtures.account())
    private val throttle = FakeThrottle()
    private val changelog = FakeChangelog()
    private val transactions = FakeTransactions(users, changelog)
    private val sessions =
        mockk<UserSessionsPort> { every { endAllExcept(any()) } returns AuthSideEffectResult.Success }
    private val useCase =
        ChangePasswordUseCase(
            VerifyPasswordUseCase(users, FakeHasher, throttle, AuthFixtures.clock),
            users,
            FakeHasher,
            sessions,
            changelog,
            transactions,
            AuthFixtures.clock,
        )

    private fun change(
        current: String = PASSWORD,
        new: String = newPassword,
    ) = useCase.execute(PasswordChangeRequest(current, new, session, client))

    @Test
    fun `the new password replaces the old one, is recorded and ends the other sessions`() {
        change() shouldBe PasswordChangeResult.Changed

        users.account?.passwordHash shouldBe FakeHasher.hashOf(newPassword)
        users.account?.passwordChangedAt shouldBe NOW
        changelog.entries.single().let {
            it.entity shouldBe UserAccount.ENTITY
            it.actor shouldBe Actor.User
            it.change.description shouldBe "Changed the login password"
        }
        verify { sessions.endAllExcept(session) }
    }

    @Test
    fun `the current password is required`() {
        change(current = "not the password") shouldBe PasswordChangeResult.WrongCurrentPassword
        change(current = "") shouldBe PasswordChangeResult.WrongCurrentPassword

        users.account shouldBe AuthFixtures.account()
        throttle.resets.shouldBeEmpty()
        verify(exactly = 0) { sessions.endAllExcept(any()) }
    }

    @Test
    fun `the new password must meet the policy`() {
        change(new = "short") shouldBe PasswordChangeResult.WeakPassword(PasswordPolicyCheck.TooShort(15))

        users.account shouldBe AuthFixtures.account()
    }

    @Test
    fun `wrong guesses of the current password are throttled`() {
        throttle.throttled = ThrottleDecision.Throttled(Duration.ofMinutes(1))

        change() shouldBe PasswordChangeResult.Throttled(Duration.ofMinutes(1))
    }

    @Test
    fun `without its changelog entry the change is rolled back and sessions stay`() {
        changelog.failing = true

        change() shouldBe PasswordChangeResult.StorageFailure

        users.account shouldBe AuthFixtures.account()
        verify(exactly = 0) { sessions.endAllExcept(any()) }
    }

    @Test
    fun `other sessions that could not be ended are reported, not success`() {
        every { sessions.endAllExcept(any()) } returns AuthSideEffectResult.Failure

        change() shouldBe PasswordChangeResult.ChangedButOtherSessionsRemain
        users.account?.passwordHash shouldBe FakeHasher.hashOf(newPassword)
    }

    @Test
    fun `failures of the store are reported`() {
        users.account = null
        change() shouldBe PasswordChangeResult.NotSetUp

        users.failing = true
        change() shouldBe PasswordChangeResult.StorageFailure
    }
}
