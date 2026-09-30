// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming

// How the task use cases record their changes in the changelog (spec §13, entity `task`). Titles and notes are the
// user's words and may describe other people: entries name them when they change, never their text (ADR-0049).

/** Appends one changelog entry for [task]; false if the store refused it (the caller then rolls back). */
internal fun ChangelogPort.record(
    task: Task,
    actor: Actor,
    description: String,
    fields: List<FieldChange> = emptyList(),
): Boolean =
    append(ChangelogEntry(task.id.toEntityRef(), actor, task.updatedAt, ChangeSummary(description, fields))) is
        ChangelogResult.Success

/** Records the state change from [before] to [after] as [description]; nothing if the task did not move. */
internal fun ChangelogPort.recordMove(
    before: Task,
    after: Task,
    actor: Actor,
    description: String,
): TaskResult<Task> {
    if (after == before) return TaskResult.Success(before)
    val state = listOf(FieldChange("state", before.state.name, after.state.name))
    return after.taskIf(record(after, actor, description, state), "changelog")
}

/** What changed between two versions of the details, with values: the timing and the link (ids only). */
internal fun detailChanges(
    before: TaskDetails?,
    after: TaskDetails,
): List<FieldChange> =
    listOfNotNull(
        changeOf("timing", before?.timing?.let(::timingText), timingText(after.timing)),
        changeOf("link", before?.link?.let(::linkText), after.link?.let(::linkText)),
    )

/** [action], plus the names of the title and the notes if they changed; their text never goes in. */
internal fun describe(
    action: String,
    before: TaskDetails,
    after: TaskDetails,
): String {
    val named =
        listOfNotNull(
            "title".takeIf { before.title != after.title },
            "notes".takeIf { before.notes != after.notes },
        )
    return if (named.isEmpty()) action else "$action; also changed: ${named.joinToString()}"
}

private fun changeOf(
    field: String,
    before: String?,
    after: String?,
): FieldChange? = if (before == after) null else FieldChange(field, before, after)

/** `2026-10-05T08:00:00Z Europe/Berlin`, `WEEK 2026-09-28` or `SOMEDAY`. */
private fun timingText(timing: TaskTiming): String =
    when (timing) {
        is TaskTiming.Exact -> "${timing.dueAt} ${timing.zone.id}"
        is TaskTiming.Bucket -> listOfNotNull(timing.span.name, timing.startsOn?.toString()).joinToString(" ")
    }

/** `application:<id>`, `company:<id>` or `contact:<id>`. */
private fun linkText(link: TaskLink): String {
    val kind =
        when (link) {
            is ApplicationRef -> "application"
            is CompanyRef -> "company"
            is ContactRef -> "contact"
        }
    return "$kind:${link.value}"
}
