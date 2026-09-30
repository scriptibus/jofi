// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application

import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationBinding
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The server-enforced two-step confirmation for deletes and outward-facing actions (AGENTS.md §6,
 * spec §9, ADR-0039). A feature use case calls it first, with the action it is about to run, and
 * executes only with the [ConfirmationResult.Confirmed] it returns, which destructive port methods
 * require as a parameter; every other result goes back to its caller unchanged. Called without a
 * token, it issues one bound to requester, operation, targets and effect that expires after
 * [timeToLive]; called with a token, it spends that token.
 */
class ConfirmActionUseCase(
    private val store: ConfirmationStorePort,
    private val clock: Clock,
    private val timeToLive: Duration,
) {
    init {
        require(!timeToLive.isNegative && !timeToLive.isZero) { "The confirmation time to live must be positive" }
    }

    fun execute(request: ConfirmationRequest): ConfirmationResult {
        val now = clock.instant()
        val token = request.token ?: return issue(request, now)
        return store.redeem(token)?.check(request.requester, request.action, now)
            ?: ConfirmationResult.Rejected(ConfirmationRejection.UNKNOWN)
    }

    private fun issue(
        request: ConfirmationRequest,
        now: Instant,
    ): ConfirmationResult.Required {
        val expiresAt = now.plus(timeToLive)
        val binding = ConfirmationBinding.of(request.requester, request.action)
        val token = store.issue(PendingConfirmation(binding, expiresAt), now)
        return ConfirmationResult.Required(token, expiresAt, request.action)
    }
}
