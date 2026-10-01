// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.tasks.application.port.CountdownRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.ListCountdownsPort
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.TaskResult

/** The custom countdowns, soonest target first, past ones included. Reads only. */
class ListCountdownsUseCase(
    private val countdowns: CountdownRepositoryPort,
) : ListCountdownsPort {
    override fun execute(): TaskResult<List<Countdown>> = countdowns.list().toCountdownResult()
}
