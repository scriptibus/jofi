// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationTimelineRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.GetApplicationTimelinePort
import io.github.scriptibus.jofi.applications.application.port.spi.LinkedTasksPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.TimelineEntry
import io.github.scriptibus.jofi.applications.domain.TimelineEntryKind
import io.github.scriptibus.jofi.applications.domain.TimelinePage
import io.github.scriptibus.jofi.applications.domain.TimelinePosition
import io.github.scriptibus.jofi.applications.domain.TimelineQuery

/**
 * The application's timeline (#87): the entries of its own sources and the tasks linked to it, each read once for
 * the page (one more than it shows, to know whether another page follows), merged newest first.
 */
class GetApplicationTimelineUseCase(
    private val timeline: ApplicationTimelineRepositoryPort,
    private val tasks: LinkedTasksPort,
) : GetApplicationTimelinePort {
    override fun execute(
        id: ApplicationId,
        query: TimelineQuery,
    ): ApplicationResult<TimelinePage> =
        timeline.entries(id, query).toResult().then { own ->
            when (val linked = tasks.linkedTasks(id.value, query.before?.let(::tasksBefore), query.fetchSize)) {
                is LinkedTasksPort.Tasks.Listed -> {
                    ApplicationResult.Success(TimelinePage.merge(query, own, linked.tasks.map(::entryOf)))
                }

                LinkedTasksPort.Tasks.Unavailable -> {
                    ApplicationResult.StorageFailure("linkedTasks")
                }
            }
        }

    /**
     * Tasks come last among the entries of one instant ([TimelineEntryKind.TASK]), so after another kind's position
     * the tasks of that instant were on the page already: only older ones follow.
     */
    private fun tasksBefore(position: TimelinePosition): LinkedTasksPort.Before =
        LinkedTasksPort.Before(
            position.occurredAt,
            if (position.kind == TimelineEntryKind.TASK) position.uuid() else null,
        )

    private fun entryOf(task: LinkedTasksPort.LinkedTask): TimelineEntry =
        TimelineEntry.TaskAdded(task.id, task.createdAt, task.title, task.completedAt)
}
