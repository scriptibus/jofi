// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

import io.github.scriptibus.jofi.system.domain.LoginBackoff
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import java.time.Instant

/** Counts failed password checks per [ThrottleKey] (threat model T5). Implementations never throw. */
interface LoginThrottlePort {
    /**
     * Atomically decides whether an attempt of [key] at [now] may run under [policy]. An allowed
     * attempt counts as failed right away (see `FailedAttempts`) until [reset] clears the key.
     */
    fun attempt(
        key: ThrottleKey,
        policy: LoginBackoff,
        now: Instant,
    ): ThrottleDecision

    /** Forgets the failures of [key] after a successful check. */
    fun reset(key: ThrottleKey)
}
