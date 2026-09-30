// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.TaskRecord
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.BucketSpan
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.Task
import io.github.scriptibus.jofi.tasks.domain.TaskDetails
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskState
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Maps tasks to `task` rows and back, explicitly and without business logic. */
internal object TaskRecords {
    private const val MANUAL = "MANUAL"
    private const val CHAT = "CHAT"
    private const val SUGGESTED = "SUGGESTED"

    fun toRecord(task: Task): TaskRecord =
        TaskRecord().apply {
            val details = task.details
            val timing = details.timing
            val link = details.link
            val origin = task.origin
            id = task.id.value
            title = details.title
            notes = details.notes
            dueAt = (timing as? TaskTiming.Exact)?.dueAt?.toUtc()
            timeZone = (timing as? TaskTiming.Exact)?.zone?.id
            bucketSpan = (timing as? TaskTiming.Bucket)?.span?.name
            bucketStartsOn = (timing as? TaskTiming.Bucket)?.startsOn
            applicationId = (link as? ApplicationRef)?.value
            companyId = (link as? CompanyRef)?.value
            contactId = (link as? ContactRef)?.value
            this.origin = originName(origin)
            suggestionRule = (origin as? TaskOrigin.Suggested)?.rule
            suggestionKey = (origin as? TaskOrigin.Suggested)?.key
            state = task.state.name
            completedAt = task.completedAt?.toUtc()
            version = task.version
            createdAt = task.createdAt.toUtc()
            updatedAt = task.updatedAt.toUtc()
        }

    fun toDomain(record: TaskRecord): Task =
        Task(
            id = TaskId(record.id),
            details = TaskDetails(record.title, timingOf(record), linkOf(record), record.notes),
            origin = originOf(record),
            state = TaskState.valueOf(record.state),
            completedAt = record.completedAt?.toInstant(),
            version = record.version,
            createdAt = record.createdAt.toInstant(),
            updatedAt = record.updatedAt.toInstant(),
        )

    // `task_timing_valid`: an instant with its zone, or a bucket.
    private fun timingOf(record: TaskRecord): TaskTiming =
        record.dueAt?.let { TaskTiming.Exact(it.toInstant(), ZoneId.of(record.timeZone)) }
            ?: TaskTiming.Bucket(BucketSpan.valueOf(record.bucketSpan), record.bucketStartsOn)

    // `task_single_link`: one of the three at most.
    private fun linkOf(record: TaskRecord) =
        record.applicationId?.let(::ApplicationRef)
            ?: record.companyId?.let(::CompanyRef)
            ?: record.contactId?.let(::ContactRef)

    private fun originName(origin: TaskOrigin): String =
        when (origin) {
            TaskOrigin.Manual -> MANUAL
            TaskOrigin.Chat -> CHAT
            is TaskOrigin.Suggested -> SUGGESTED
        }

    // `task_suggestion_matches_origin`: a suggestion has its rule and key.
    private fun originOf(record: TaskRecord): TaskOrigin =
        when (record.origin) {
            MANUAL -> TaskOrigin.Manual
            CHAT -> TaskOrigin.Chat
            SUGGESTED -> TaskOrigin.Suggested(record.suggestionRule, record.suggestionKey)
            else -> error("Unknown task origin")
        }

    private fun Instant.toUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
