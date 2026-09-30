// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class LoginBackoffTest {
    private val policy =
        LoginBackoff(freeAttempts = 3, initialDelay = Duration.ofSeconds(1), maxDelay = Duration.ofSeconds(10))
    private val start = Instant.parse("2026-09-30T10:00:00Z")

    @Test
    fun `the first failures are free, then the delay doubles up to the cap`() {
        (0..3).map(policy::delayAfter) shouldBe List(4) { Duration.ZERO }
        policy.delayAfter(4) shouldBe Duration.ofSeconds(1)
        policy.delayAfter(5) shouldBe Duration.ofSeconds(2)
        policy.delayAfter(6) shouldBe Duration.ofSeconds(4)
        policy.delayAfter(8) shouldBe Duration.ofSeconds(10)
        policy.delayAfter(Int.MAX_VALUE) shouldBe Duration.ofSeconds(10)
    }

    @Test
    fun `an allowed attempt counts as failed until it is reset`() {
        val (after, decision) = FailedAttempts.none(start).attempt(policy, start)

        decision shouldBe ThrottleDecision.Allowed
        after shouldBe FailedAttempts(1, start)
    }

    @Test
    fun `an attempt inside the backoff window is refused with the remaining wait and not counted`() {
        val attempts = FailedAttempts(count = 4, lastAttemptAt = start)

        val (after, decision) = attempts.attempt(policy, start.plusMillis(400))

        decision shouldBe ThrottleDecision.Throttled(Duration.ofMillis(600))
        after shouldBe attempts
        attempts.attempt(policy, start.plusSeconds(1)).second shouldBe ThrottleDecision.Allowed
    }

    @Test
    fun `the production policies throttle a single client sooner than all clients together`() {
        LoginThrottling.PER_CLIENT.delayAfter(5) shouldBe Duration.ZERO
        LoginThrottling.PER_CLIENT.delayAfter(6) shouldBe Duration.ofSeconds(1)
        LoginThrottling.PER_CLIENT.delayAfter(100) shouldBe Duration.ofMinutes(15)
        LoginThrottling.EVERYONE.delayAfter(50) shouldBe Duration.ZERO
        LoginThrottling.EVERYONE.delayAfter(100) shouldBe Duration.ofMinutes(1)
    }

    @Test
    fun `invalid policies and counts are rejected`() {
        shouldThrow<IllegalArgumentException> { LoginBackoff(-1, Duration.ofSeconds(1), Duration.ofSeconds(1)) }
        shouldThrow<IllegalArgumentException> { LoginBackoff(1, Duration.ZERO, Duration.ofSeconds(1)) }
        shouldThrow<IllegalArgumentException> { LoginBackoff(1, Duration.ofSeconds(2), Duration.ofSeconds(1)) }
        shouldThrow<IllegalArgumentException> { FailedAttempts(-1, start) }
    }
}
