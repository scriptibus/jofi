// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.github.scriptibus.jofi.tasks.domain.Countdown
import io.github.scriptibus.jofi.tasks.domain.CountdownDetails
import io.github.scriptibus.jofi.tasks.domain.CountdownId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskStoreResult
import java.time.Instant

// How the countdown use cases (#112) chain their steps and record their changes (entity `countdown`). The title is
// the user's words: entries name it when it changes, never its text; the target date goes in with its values.

/** As [toResult], but an unknown id is [TaskResult.CountdownNotFound]. */
internal fun <T> TaskStoreResult<T>.toCountdownResult(): TaskResult<T> =
    if (this == TaskStoreResult.NotFound) TaskResult.CountdownNotFound else toResult()

/** The countdown if the caller based its change on its current version, else [TaskResult.VersionConflict]. */
internal fun Countdown.basedOn(version: Long): TaskResult<Countdown> =
    if (this.version == version) TaskResult.Success(this) else TaskResult.VersionConflict

/** Appends one changelog entry about [id] [at]; false if the store refused it (the caller then rolls back). */
internal fun ChangelogPort.recordCountdown(
    id: CountdownId,
    actor: Actor,
    at: Instant,
    description: String,
    fields: List<FieldChange> = emptyList(),
): Boolean =
    append(ChangelogEntry(id.toEntityRef(), actor, at, ChangeSummary(description, fields))) is ChangelogResult.Success

/** The target date's change, if any; the title only by name in [describeCountdown]. */
internal fun countdownChanges(
    before: CountdownDetails?,
    after: CountdownDetails,
): List<FieldChange> =
    if (before?.targetDate == after.targetDate) {
        emptyList()
    } else {
        listOf(FieldChange("targetDate", before?.targetDate?.toString(), after.targetDate.toString()))
    }

/** [action], plus the title's name if it changed. */
internal fun describeCountdown(
    action: String,
    before: CountdownDetails,
    after: CountdownDetails,
): String = if (before.title == after.title) action else "$action; also changed: title"
