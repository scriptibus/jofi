// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.throttle

import io.github.scriptibus.jofi.system.domain.LoginBackoff
import io.github.scriptibus.jofi.system.domain.ThrottleCheck
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class InMemoryLoginThrottleAdapterTest {
    private val throttle = InMemoryLoginThrottleAdapter()
    private val policy =
        LoginBackoff(freeAttempts = 2, initialDelay = Duration.ofSeconds(1), maxDelay = Duration.ofMinutes(1))
    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private val client = ThrottleKey.Client("192.0.2.1")

    private fun attempt(
        key: ThrottleKey,
        at: Instant = now,
    ) = throttle.attempt(listOf(ThrottleCheck(key, policy)), at)

    private fun attemptWithEveryone(
        key: ThrottleKey,
        at: Instant = now,
    ) = throttle.attempt(listOf(ThrottleCheck(key, policy), ThrottleCheck(ThrottleKey.Everyone, policy)), at)

    @Test
    fun `free attempts pass, then the key waits until the backoff is over`() {
        repeat(3) { attempt(client) shouldBe ThrottleDecision.Allowed }

        attempt(client) shouldBe ThrottleDecision.Throttled(Duration.ofSeconds(1))
        attempt(ThrottleKey.Client("192.0.2.2")) shouldBe ThrottleDecision.Allowed
        attempt(client, now.plusSeconds(1)) shouldBe ThrottleDecision.Allowed
    }

    @Test
    fun `a reset clears the key`() {
        repeat(3) { attempt(client) }

        throttle.reset(client)

        attempt(client) shouldBe ThrottleDecision.Allowed
    }

    @Test
    fun `a global backoff refuses without charging the client, so the client is free once it clears`() {
        repeat(3) { index -> attemptWithEveryone(ThrottleKey.Client("198.51.100.$index")) }

        repeat(6) { attemptWithEveryone(client) shouldBe ThrottleDecision.Throttled(Duration.ofSeconds(1)) }

        attemptWithEveryone(client, now.plusSeconds(1)) shouldBe ThrottleDecision.Allowed
    }

    @Test
    fun `a throttled client charges nothing to everyone`() {
        val lenient =
            LoginBackoff(freeAttempts = 4, initialDelay = Duration.ofSeconds(1), maxDelay = Duration.ofMinutes(1))

        fun attemptOf(key: ThrottleKey) =
            throttle.attempt(listOf(ThrottleCheck(key, policy), ThrottleCheck(ThrottleKey.Everyone, lenient)), now)
        repeat(3) { attemptOf(client) }
        repeat(20) { attemptOf(client) shouldBe ThrottleDecision.Throttled(Duration.ofSeconds(1)) }

        // Everyone has counted only the 3 attempts let through, within its 4 free ones.
        attemptOf(ThrottleKey.Client("192.0.2.9")) shouldBe ThrottleDecision.Allowed
        attemptOf(ThrottleKey.Client("192.0.2.10")) shouldBe ThrottleDecision.Allowed
    }

    @Test
    fun `parallel guesses are counted one by one`() {
        val decisions =
            Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                pool.invokeAll(List(50) { Callable { attempt(client) } }).map { it.get() }
            }

        // At one instant only the attempts the policy allows get through, not all 50.
        decisions.count { it == ThrottleDecision.Allowed } shouldBe 3
    }
}
