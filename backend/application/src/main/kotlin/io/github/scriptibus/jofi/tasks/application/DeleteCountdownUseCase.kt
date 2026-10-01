// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.tasks.application.port.CountdownRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.DeleteCountdownPort
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.Clock

/**
 * Deletes a custom countdown in two steps (ADR-0039). The countdown is read in the transaction of the delete and the
 * confirmation effect (its title) is built from that read, so renaming it between the steps voids the token. The
 * changelog entry keeps only the id.
 */
class DeleteCountdownUseCase(
    private val countdowns: CountdownRepositoryPort,
    private val confirmation: ConfirmActionUseCase,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : DeleteCountdownPort {
    override fun execute(
        id: CountdownId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): TaskResult<Unit> =
        transactions.inTaskTransaction {
            countdowns.findById(id).toCountdownResult().then { confirmThenDelete(it, requester, token) }
        }

    private fun confirmThenDelete(
        countdown: Countdown,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): TaskResult<Unit> {
        val effect = ConfirmationEffect(CountdownId.ENTITY_TYPE, countdown.details.title)
        val action = ConfirmableAction(Countdown.DELETE_OPERATION, listOf(countdown.id.value.toString()), effect)
        return when (val outcome = confirmation.execute(ConfirmationRequest(requester, action, token))) {
            is ConfirmationResult.Confirmed -> {
                countdowns.delete(countdown.id, outcome).toCountdownResult().then {
                    val recorded =
                        changelog.recordCountdown(countdown.id, requester.actor, clock.storedNow(), "Deleted countdown")
                    Unit.taskIf(recorded, "changelog")
                }
            }

            is ConfirmationResult.Unconfirmed -> {
                TaskResult.Unconfirmed(outcome)
            }
        }
    }
}
