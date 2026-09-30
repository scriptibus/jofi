// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.throttle

import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.domain.FailedAttempts
import io.github.scriptibus.jofi.system.domain.LoginBackoff
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Failed password checks in memory. Only the `app` container serves logins, and a restart is not
 * something an attacker controls, so the counts need no database. `compute` makes every decision
 * atomic per key: parallel guesses are counted one by one. At most [MAX_KEYS] clients are
 * remembered; beyond that, entries idle for a day go first, then all per-client entries (the
 * [ThrottleKey.Everyone] count always stays).
 */
@Component
class InMemoryLoginThrottleAdapter : LoginThrottlePort {
    private val failures = ConcurrentHashMap<ThrottleKey, FailedAttempts>()

    override fun attempt(
        key: ThrottleKey,
        policy: LoginBackoff,
        now: Instant,
    ): ThrottleDecision {
        if (failures.size >= MAX_KEYS) evict(now)
        var decision: ThrottleDecision = ThrottleDecision.Allowed
        failures.compute(key) { _, current ->
            val (next, outcome) = (current ?: FailedAttempts.none(now)).attempt(policy, now)
            decision = outcome
            next
        }
        return decision
    }

    override fun reset(key: ThrottleKey) {
        failures.remove(key)
    }

    private fun evict(now: Instant) {
        val idleSince = now.minus(IDLE)
        failures.entries.removeIf { (key, attempts) ->
            key is ThrottleKey.Client &&
                attempts.lastAttemptAt.isBefore(idleSince)
        }
        if (failures.size >= MAX_KEYS) failures.keys.removeIf { it is ThrottleKey.Client }
    }

    private companion object {
        const val MAX_KEYS = 10_000
        val IDLE: Duration = Duration.ofDays(1)
    }
}
