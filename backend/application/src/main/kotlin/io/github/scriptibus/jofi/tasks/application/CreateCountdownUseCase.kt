// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.port.CountdownRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.CreateCountdownPort
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.CountdownInput
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.Clock
import java.util.UUID

/** Creates a custom countdown (spec §10.1); it and its changelog entry are stored together. */
class CreateCountdownUseCase(
    private val countdowns: CountdownRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : CreateCountdownPort {
    override fun execute(
        input: CountdownInput,
        actor: Actor,
    ): TaskResult<Countdown> {
        val now = clock.storedNow()
        return input.validate().toResult().then { details ->
            val countdown = Countdown.create(CountdownId(UUID.randomUUID()), details, now)
            transactions.inTaskTransaction {
                countdowns.add(countdown).toCountdownResult().then {
                    val fields = countdownChanges(null, details)
                    val recorded = changelog.recordCountdown(countdown.id, actor, now, "Created countdown", fields)
                    countdown.taskIf(recorded, "changelog")
                }
            }
        }
    }
}
