// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.application

import io.github.scriptibus.jofi.fixture.application.port.FixtureThingsPort
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationBinding
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import java.time.Instant

/** Known-bad: deletes through a port without the gate. Test fixture only. */
class DeleteThingWithoutGateUseCase(
    private val things: FixtureThingsPort,
) {
    fun execute(id: String) = things.delete(id)
}

/** Known-good: holds the gate (and must only delete on `Confirmed`, which the use case test proves). */
class DeleteThingWithGateUseCase(
    private val things: FixtureThingsPort,
    private val confirmAction: ConfirmActionUseCase,
) {
    fun execute(id: String) = things.delete(id + confirmAction.hashCode())
}

/** Known-good: the port method itself demands the proof. */
class SendThingWithProofUseCase(
    private val things: FixtureThingsPort,
) {
    fun execute(
        id: String,
        proof: ConfirmationResult.Confirmed,
    ) = things.send(id, proof)
}

/** Known-bad: confirms an action itself instead of asking the gate. */
class ForgedConfirmationUseCase {
    fun execute(
        requester: ConfirmationRequester,
        action: ConfirmableAction,
    ): ConfirmationResult {
        val pending = PendingConfirmation(ConfirmationBinding.of(requester, action), Instant.MAX)
        return pending.check(requester, action, Instant.MIN)
    }
}
