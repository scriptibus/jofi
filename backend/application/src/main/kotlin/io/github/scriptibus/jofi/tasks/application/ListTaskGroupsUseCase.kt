// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.RedactForAiUseCase
import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.tasks.application.port.TaskRepositoryPort
import io.github.scriptibus.jofi.tasks.application.port.inbound.ListTaskGroupsPort
import io.github.scriptibus.jofi.tasks.domain.TaskCalendar
import io.github.scriptibus.jofi.tasks.domain.TaskGroupsPage
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskState
import java.time.Clock
import java.time.ZoneId

/**
 * One page of the open tasks grouped on the viewer's calendar ([TaskCalendar], ADR-0049, ADR-0056): the groups depend
 * on the viewer's zone and the clock's "now", so all open tasks are read and grouped, then the page is cut from the
 * grouped sequence and notes shortened to excerpts (for an AI, after its filter). Done tasks and suggestions (pending
 * or dismissed) are not in the list.
 */
class ListTaskGroupsUseCase(
    private val tasks: TaskRepositoryPort,
    private val clock: Clock,
    private val redaction: RedactForAiUseCase,
) : ListTaskGroupsPort {
    override fun execute(
        zone: ZoneId,
        page: PageInput,
        audience: NotesAudience,
    ): TaskResult<TaskGroupsPage> =
        page.toResult().then { request ->
            val calendar = TaskCalendar(clock.storedNow(), zone)
            tasks.listByState(TaskState.OPEN).toResult().then { open ->
                val window = TaskGroupsPage.window(calendar.group(open), request)
                summariesOf(window.tasks, audience, redaction).then { TaskResult.Success(window.page(it)) }
            }
        }
}
