// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.system.application.AuthFixtures.NOW
import io.github.scriptibus.jofi.system.application.AuthFixtures.PASSWORD
import io.github.scriptibus.jofi.system.application.AuthFixtures.client
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.github.scriptibus.jofi.system.domain.FirstRunResult
import io.github.scriptibus.jofi.system.domain.PasswordPolicyCheck
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.UserAccount
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration

class CompleteFirstRunUseCaseTest {
    private val users = FakeUsers()
    private val throttle = FakeThrottle()
    private val changelog = FakeChangelog()
    private val transactions = FakeTransactions(users, changelog)
    private val setupToken =
        mockk<SetupTokenPort> {
            every { isRequired() } returns false
            every { matches(any()) } answers { firstArg<String>() == "the-token" }
            every { discard() } returns AuthSideEffectResult.Success
        }
    private val useCase =
        CompleteFirstRunUseCase(users, FakeHasher, setupToken, throttle, changelog, transactions, AuthFixtures.clock)

    @Test
    fun `first run stores the hashed password and records it as the user's change`() {
        useCase.execute(PASSWORD, null, client) shouldBe FirstRunResult.Completed

        users.account shouldBe UserAccount(FakeHasher.hashOf(PASSWORD), NOW, NOW)
        changelog.entries.single().let {
            it.entity shouldBe UserAccount.ENTITY
            it.actor shouldBe Actor.User
            it.occurredAt shouldBe NOW
            it.change.description shouldBe "Set the login password (first run)"
        }
        verify { setupToken.discard() }
    }

    @Test
    fun `once a password exists first run is over`() {
        users.account = AuthFixtures.account("an earlier password!")

        useCase.execute(PASSWORD, null, client) shouldBe FirstRunResult.AlreadySetUp
        users.account shouldBe AuthFixtures.account("an earlier password!")
    }

    @Test
    fun `when exposed the setup token must be given and match`() {
        every { setupToken.isRequired() } returns true

        useCase.execute(PASSWORD, null, client) shouldBe FirstRunResult.InvalidSetupToken
        useCase.execute(PASSWORD, "guess", client) shouldBe FirstRunResult.InvalidSetupToken
        users.account.shouldBeNull()
        useCase.execute(PASSWORD, "the-token", client) shouldBe FirstRunResult.Completed
    }

    @Test
    fun `a weak password is refused without storing anything`() {
        useCase.execute("too short", null, client) shouldBe
            FirstRunResult.WeakPassword(PasswordPolicyCheck.TooShort(15))

        users.account.shouldBeNull()
        changelog.entries.shouldBeEmpty()
    }

    @Test
    fun `attempts are throttled like logins`() {
        throttle.throttled = ThrottleDecision.Throttled(Duration.ofSeconds(3))

        useCase.execute(PASSWORD, null, client) shouldBe FirstRunResult.Throttled(Duration.ofSeconds(3))
        users.account.shouldBeNull()
    }

    @Test
    fun `without its changelog entry the account is rolled back`() {
        changelog.failing = true

        useCase.execute(PASSWORD, null, client) shouldBe FirstRunResult.StorageFailure

        transactions.rolledBack shouldBe true
        users.account.shouldBeNull()
        verify(exactly = 0) { setupToken.discard() }
    }

    @Test
    fun `a storage failure is reported as such`() {
        users.failing = true

        useCase.execute(PASSWORD, null, client) shouldBe FirstRunResult.StorageFailure
    }
}
