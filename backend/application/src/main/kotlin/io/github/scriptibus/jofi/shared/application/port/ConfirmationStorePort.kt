// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import java.time.Instant

/**
 * Holds first steps of destructive or outward-facing actions until they are confirmed (spec §9,
 * ADR-0039). Server-side only: a client never holds more than the token. Implementations never
 * throw and never log tokens.
 */
interface ConfirmationStorePort {
    /**
     * Keeps [pending] under a new token of at least 256 random bits from a CSPRNG. The store is
     * bounded: expired entries (at [now]) go first, then the oldest ones.
     */
    fun issue(
        pending: PendingConfirmation,
        now: Instant,
    ): ConfirmationToken

    /**
     * Removes and returns what [token] was issued for, or `null` if it is unknown. Atomic, and
     * every presentation spends the token, so it works at most once even if it then does not match.
     */
    fun redeem(token: ConfirmationToken): PendingConfirmation?
}
