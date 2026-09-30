// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.throttle

import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.domain.FailedAttempts
import io.github.scriptibus.jofi.system.domain.ThrottleCheck
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Failed password checks in memory. Only the `app` container serves logins, and a restart is not
 * something an attacker controls, so the counts need no database. One lock makes every decision
 * atomic across all counters it involves: parallel guesses are counted one by one, and a refused
 * attempt charges none of them. At most [MAX_KEYS] clients are remembered; beyond that, entries idle
 * for a day go first, then all per-client entries (the [ThrottleKey.Everyone] count always stays).
 */
@Component
class InMemoryLoginThrottleAdapter : LoginThrottlePort {
    private val failures = HashMap<ThrottleKey, FailedAttempts>()

    @Synchronized
    override fun attempt(
        checks: List<ThrottleCheck>,
        now: Instant,
    ): ThrottleDecision {
        if (failures.size >= MAX_KEYS) evict(now)
        val nextAllowed = checks.map { current(it.key, now).nextAttemptAt(it.policy) }.filter { now.isBefore(it) }
        if (nextAllowed.isNotEmpty()) return ThrottleDecision.Throttled(Duration.between(now, nextAllowed.max()))
        checks.forEach { failures[it.key] = current(it.key, now).counted(now) }
        return ThrottleDecision.Allowed
    }

    @Synchronized
    override fun reset(key: ThrottleKey) {
        failures.remove(key)
    }

    private fun current(
        key: ThrottleKey,
        now: Instant,
    ): FailedAttempts = failures[key] ?: FailedAttempts.none(now)

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
