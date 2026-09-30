// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.domain.LoginThrottling
import io.github.scriptibus.jofi.system.domain.ThrottleCheck
import io.github.scriptibus.jofi.system.domain.ThrottleDecision
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.github.scriptibus.jofi.system.domain.UserAccount
import java.time.Instant

/**
 * Lets a password check of [client] run, or says how long to wait. The client's counter and the count
 * of all clients are checked together and charged only when both let the attempt through: a throttled
 * client never keeps the global backoff armed, and a global backoff never adds to the owner's own
 * count (ADR-0035).
 */
internal fun LoginThrottlePort.attemptFor(
    client: ThrottleKey.Client,
    now: Instant,
): ThrottleDecision =
    attempt(
        listOf(
            ThrottleCheck(client, LoginThrottling.PER_CLIENT),
            ThrottleCheck(ThrottleKey.Everyone, LoginThrottling.EVERYONE),
        ),
        now,
    )

/** Clears the counts after a correct password. */
internal fun LoginThrottlePort.resetFor(client: ThrottleKey.Client) {
    reset(client)
    reset(ThrottleKey.Everyone)
}

/** Records a change of the user's password in the audit trail; the user always acts here. */
internal fun ChangelogPort.recordPasswordChange(
    description: String,
    at: Instant,
): Boolean {
    val entry =
        ChangelogEntry(
            entity = UserAccount.ENTITY,
            actor = Actor.User,
            occurredAt = at,
            change = ChangeSummary(description),
        )
    return append(entry) is ChangelogResult.Success
}
