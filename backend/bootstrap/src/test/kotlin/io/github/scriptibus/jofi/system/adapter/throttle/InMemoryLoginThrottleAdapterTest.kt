// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.throttle

import io.github.scriptibus.jofi.system.domain.LoginBackoff
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

    @Test
    fun `free attempts pass, then the key waits until the backoff is over`() {
        repeat(3) { throttle.attempt(client, policy, now) shouldBe ThrottleDecision.Allowed }

        throttle.attempt(client, policy, now) shouldBe ThrottleDecision.Throttled(Duration.ofSeconds(1))
        throttle.attempt(ThrottleKey.Client("192.0.2.2"), policy, now) shouldBe ThrottleDecision.Allowed
        throttle.attempt(client, policy, now.plusSeconds(1)) shouldBe ThrottleDecision.Allowed
    }

    @Test
    fun `a reset clears the key`() {
        repeat(3) { throttle.attempt(client, policy, now) }

        throttle.reset(client)

        throttle.attempt(client, policy, now) shouldBe ThrottleDecision.Allowed
    }

    @Test
    fun `parallel guesses are counted one by one`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val decisions =
            executor.use { pool ->
                pool.invokeAll(List(50) { Callable { throttle.attempt(client, policy, now) } }).map { it.get() }
            }

        // At one instant only the attempts the policy allows get through, not all 50.
        decisions.count { it == ThrottleDecision.Allowed } shouldBe 3
    }
}
