// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.AuthFixtures.PASSWORD
import io.github.scriptibus.jofi.system.application.AuthFixtures.client
import io.github.scriptibus.jofi.system.domain.LoginResult
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class LogInUseCaseTest {
    private val users = FakeUsers(AuthFixtures.account())
    private val throttle = FakeThrottle()
    private val useCase = LogInUseCase(users, FakeHasher, throttle, AuthFixtures.clock)

    @Test
    fun `the right password logs in and clears the failure counts`() {
        useCase.execute(PASSWORD, client) shouldBe LoginResult.LoggedIn(AuthFixtures.ACCOUNT_ID)

        throttle.attempts shouldContainExactly listOf(client, ThrottleKey.Everyone)
        throttle.resets shouldContainExactly listOf(client, ThrottleKey.Everyone)
    }

    @Test
    fun `a wrong, empty or oversized password is refused and stays counted`() {
        listOf("wrong password", "", "x".repeat(300)).forEach {
            useCase.execute(it, client) shouldBe LoginResult.InvalidCredentials
        }

        throttle.resets.shouldBeEmpty()
    }

    @Test
    fun `a throttled client is refused before the password is looked at and does not charge everyone`() {
        throttle.throttled = ThrottleDecision.Throttled(Duration.ofSeconds(8))

        useCase.execute(PASSWORD, client) shouldBe LoginResult.Throttled(Duration.ofSeconds(8))
        throttle.attempts shouldContainExactly listOf(client)
    }

    @Test
    fun `before first run there is nothing to log in to`() {
        users.account = null

        useCase.execute(PASSWORD, client) shouldBe LoginResult.NotSetUp
    }

    @Test
    fun `a storage failure is reported as such`() {
        users.failing = true

        useCase.execute(PASSWORD, client) shouldBe LoginResult.StorageFailure
    }
}
