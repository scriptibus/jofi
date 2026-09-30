// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application.port.inbound

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.CountdownInput
import io.github.scriptibus.jofi.tasks.domain.DashboardCountdown
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import java.time.ZoneId

// Inbound ports for countdowns (#80), implemented by the use cases of the same name (#112). An unknown countdown is
// `CountdownNotFound`. Mutations take the acting `Actor` and write a changelog entry (entity `countdown`) naming the
// changed fields, never the title; `basedOnVersion` works as for tasks.

interface CreateCountdownPort {
    fun execute(
        input: CountdownInput,
        actor: Actor,
    ): TaskResult<Countdown>
}

/** Replaces title and target date; unchanged details store nothing and write no changelog entry. */
interface UpdateCountdownPort {
    fun execute(
        id: CountdownId,
        input: CountdownInput,
        basedOnVersion: Long,
        actor: Actor,
    ): TaskResult<Countdown>
}

/**
 * Deletes a custom countdown in two steps (ADR-0039): the token is bound to [Countdown.DELETE_OPERATION], the id and
 * the effect `ConfirmationEffect("countdown", <title>)`. The actor is [requester]'s.
 */
interface DeleteCountdownPort {
    fun execute(
        id: CountdownId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): TaskResult<Unit>
}

/** The custom countdowns, soonest target first, past ones included (to edit or delete them). Reads only. */
interface ListCountdownsPort {
    fun execute(): TaskResult<List<Countdown>>
}

/**
 * The dashboard's countdowns (#112), soonest first: the custom ones, the next interview still to come, application
 * deadlines and offer answer deadlines from today on as seen on the calendar of [zone] (the viewer's). The other
 * contexts' data is read through their public API. Reads only.
 */
interface ListDashboardCountdownsPort {
    fun execute(zone: ZoneId): TaskResult<List<DashboardCountdown>>
}
