// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Real confirmation proofs from the gate (ADR-0039), since nothing else can mint one. */
object ConfirmedProofs {
    fun of(
        operation: String,
        target: String,
    ): ConfirmationResult.Confirmed {
        val store = SingleEntryStore()
        val gate = ConfirmActionUseCase(store, Clock.systemUTC(), Duration.ofMinutes(1))
        val requester = ConfirmationRequester(Actor.User, "test-session")
        val action = ConfirmableAction(operation, listOf(target), ConfirmationEffect("test", "test"))
        val required = gate.execute(ConfirmationRequest(requester, action, null)) as ConfirmationResult.Required
        return gate.execute(ConfirmationRequest(requester, action, required.token)) as ConfirmationResult.Confirmed
    }

    private class SingleEntryStore : ConfirmationStorePort {
        private var pending: PendingConfirmation? = null

        override fun issue(
            pending: PendingConfirmation,
            now: Instant,
        ): ConfirmationToken {
            this.pending = pending
            return ConfirmationToken("token")
        }

        override fun redeem(token: ConfirmationToken): PendingConfirmation? = pending.also { pending = null }
    }
}
