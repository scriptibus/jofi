// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.tasks.application.port.CountdownRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.UpdateCountdownPort
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownDetails
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.CountdownInput
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.Clock
import java.time.Instant

/**
 * Replaces a custom countdown's title and target date. The version is checked first; unchanged details store nothing
 * and write no changelog entry. Read and write share one transaction, and the repository's version check catches a
 * change in between.
 */
class UpdateCountdownUseCase(
    private val countdowns: CountdownRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : UpdateCountdownPort {
    override fun execute(
        id: CountdownId,
        input: CountdownInput,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Countdown> {
        val now = clock.storedNow()
        return transactions.inTaskTransaction {
            countdowns
                .findById(id)
                .toCountdownResult()
                .then { it.basedOn(basedOnVersion) }
                .then { current -> input.validate().toResult().then { edit(current, it, actor, now) } }
        }
    }

    private fun edit(
        current: Countdown,
        details: CountdownDetails,
        actor: Actor,
        now: Instant,
    ): TaskResult<Countdown> {
        val edited = current.edit(details, now)
        if (edited == current) return TaskResult.Success(current)
        return countdowns.update(edited).toCountdownResult().then {
            val description = describeCountdown("Edited countdown", current.details, details)
            val fields = countdownChanges(current.details, details)
            edited.taskIf(changelog.recordCountdown(edited.id, actor, now, description, fields), "changelog")
        }
    }
}
