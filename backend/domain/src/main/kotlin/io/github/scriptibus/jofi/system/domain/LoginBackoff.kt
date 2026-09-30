// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import java.time.Duration
import java.time.Instant

/**
 * Exponential backoff after failed password checks (threat model T5): the first [freeAttempts]
 * failures cost nothing, then every further failure doubles the wait, starting at [initialDelay] and
 * capped at [maxDelay]. It slows guessing without locking the user out for good.
 */
data class LoginBackoff(
    val freeAttempts: Int,
    val initialDelay: Duration,
    val maxDelay: Duration,
) {
    init {
        require(freeAttempts >= 0) { "Free attempts must not be negative" }
        require(!initialDelay.isNegative && !initialDelay.isZero) { "The initial delay must be positive" }
        require(maxDelay >= initialDelay) { "The maximum delay must not be below the initial delay" }
    }

    /** How long to wait after [failures] consecutive failures before the next attempt is allowed. */
    fun delayAfter(failures: Int): Duration {
        val beyondFree = failures - freeAttempts
        if (beyondFree <= 0) return Duration.ZERO
        // Doubling beyond this many steps exceeds any sensible maximum; stop before Long overflow.
        val doublings = (beyondFree - 1).coerceAtMost(MAX_DOUBLINGS)
        val delay = initialDelay.multipliedBy(1L shl doublings)
        return if (delay > maxDelay) maxDelay else delay
    }

    private companion object {
        const val MAX_DOUBLINGS = 30
    }
}

/**
 * Consecutive failed attempts of one [ThrottleKey]. An attempt counts as failed as soon as it is let
 * through, and a success resets the count; so parallel guesses cannot all slip through before the
 * first one fails.
 */
data class FailedAttempts(
    val count: Int,
    val lastAttemptAt: Instant,
) {
    init {
        require(count >= 0) { "The failure count must not be negative" }
    }

    /** When the next attempt is allowed under [policy]. */
    fun nextAttemptAt(policy: LoginBackoff): Instant = lastAttemptAt.plus(policy.delayAfter(count))

    /** Lets an attempt at [now] through (counting it as failed), or says how long to wait. */
    fun attempt(
        policy: LoginBackoff,
        now: Instant,
    ): Pair<FailedAttempts, ThrottleDecision> {
        val next = nextAttemptAt(policy)
        return if (now.isBefore(next)) {
            this to ThrottleDecision.Throttled(Duration.between(now, next))
        } else {
            FailedAttempts(count + 1, now) to ThrottleDecision.Allowed
        }
    }

    companion object {
        /** A key without failures (yet) at [now]. */
        fun none(now: Instant): FailedAttempts = FailedAttempts(0, now)
    }
}

/** Whose attempts are counted: one client address, or all attempts together. */
sealed interface ThrottleKey {
    /** One client, by its network address. Never logged (personal data). */
    data class Client(
        val address: String,
    ) : ThrottleKey {
        override fun toString(): String = "Client(***)"
    }

    /** Every attempt from anywhere, so many addresses together still slow down. */
    data object Everyone : ThrottleKey
}

/** Whether a password check may run now. */
sealed interface ThrottleDecision {
    data object Allowed : ThrottleDecision

    data class Throttled(
        val retryAfter: Duration,
    ) : ThrottleDecision
}

/** The backoff policies applied to every password check (login, first run, password change). */
object LoginThrottling {
    private const val CLIENT_FREE_ATTEMPTS = 5
    private const val CLIENT_MAX_DELAY_MINUTES = 15L
    private const val EVERYONE_FREE_ATTEMPTS = 50
    private const val EVERYONE_MAX_DELAY_MINUTES = 1L
    private val INITIAL_DELAY: Duration = Duration.ofSeconds(1)

    /** One client: a few typos are free, then up to 15 minutes between guesses. */
    val PER_CLIENT =
        LoginBackoff(CLIENT_FREE_ATTEMPTS, INITIAL_DELAY, Duration.ofMinutes(CLIENT_MAX_DELAY_MINUTES))

    /**
     * All clients together. The cap stays short because an attacker can drive this one on purpose;
     * it only has to stop many addresses from guessing in parallel.
     */
    val EVERYONE =
        LoginBackoff(EVERYONE_FREE_ATTEMPTS, INITIAL_DELAY, Duration.ofMinutes(EVERYONE_MAX_DELAY_MINUTES))
}
