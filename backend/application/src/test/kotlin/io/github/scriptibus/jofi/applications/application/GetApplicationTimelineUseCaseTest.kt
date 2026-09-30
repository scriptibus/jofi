// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationTimelineRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.spi.LinkedTasksPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.TimelineEntry
import io.github.scriptibus.jofi.applications.domain.TimelineEntryKind
import io.github.scriptibus.jofi.applications.domain.TimelinePage
import io.github.scriptibus.jofi.applications.domain.TimelinePosition
import io.github.scriptibus.jofi.applications.domain.TimelineQuery
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GetApplicationTimelineUseCaseTest {
    private val repository = mockk<ApplicationTimelineRepositoryPort>()
    private val tasks = mockk<LinkedTasksPort>()
    private val timeline = GetApplicationTimelineUseCase(repository, tasks)
    private val id = ApplicationId(UUID.randomUUID())
    private val status = TimelineEntry.StatusChanged(1, AT, Actor.User, null, ApplicationStatus.DISCOVERED, null)
    private val change = TimelineEntry.Change(2, AT.plusSeconds(2), Actor.Ai, emptyList())
    private val task = LinkedTasksPort.LinkedTask(UUID.randomUUID(), "Call Erika", AT.plusSeconds(1), null)

    @Test
    fun `merges the application's entries with its tasks, newest first, reading one more than the page`() {
        val query = TimelineQuery(limit = 2)
        every { repository.entries(id, query) } returns ApplicationStoreResult.Success(listOf(change, status))
        every { tasks.linkedTasks(id.value, null, 3) } returns LinkedTasksPort.Tasks.Listed(listOf(task))

        val added = TimelineEntry.TaskAdded(task.id, task.createdAt, task.title, null)
        timeline.execute(id, query) shouldBe
            ApplicationResult.Success(TimelinePage(listOf(change, added), added.position))
    }

    @Test
    fun `after a task the tasks continue below its id, after any other kind below its instant`() {
        val afterTask = TimelinePosition(AT, TimelineEntryKind.TASK, task.id.toString())
        val afterChange = TimelinePosition(AT, TimelineEntryKind.CHANGE, "5")
        every { repository.entries(id, any()) } returns ApplicationStoreResult.Success(emptyList())
        every { tasks.linkedTasks(id.value, any(), any()) } returns LinkedTasksPort.Tasks.Listed(emptyList())

        timeline.execute(id, TimelineQuery(afterTask)) shouldBe
            ApplicationResult.Success(TimelinePage(emptyList(), null))
        timeline.execute(id, TimelineQuery(afterChange))

        verify { tasks.linkedTasks(id.value, LinkedTasksPort.Before(AT, task.id), TimelineQuery.DEFAULT_LIMIT + 1) }
        verify { tasks.linkedTasks(id.value, LinkedTasksPort.Before(AT, null), TimelineQuery.DEFAULT_LIMIT + 1) }
    }

    @Test
    fun `an unknown application is not found without asking for tasks, unreadable tasks are a storage failure`() {
        every { repository.entries(id, any()) } returns ApplicationStoreResult.NotFound

        timeline.execute(id, TimelineQuery()) shouldBe ApplicationResult.NotFound
        verify(exactly = 0) { tasks.linkedTasks(any(), any(), any()) }

        every { repository.entries(id, any()) } returns ApplicationStoreResult.Success(listOf(status))
        every { tasks.linkedTasks(id.value, null, any()) } returns LinkedTasksPort.Tasks.Unavailable
        timeline.execute(id, TimelineQuery()) shouldBe ApplicationResult.StorageFailure("linkedTasks")
        every { repository.entries(id, any()) } returns ApplicationStoreResult.StorageFailure("timeline")
        timeline.execute(id, TimelineQuery()) shouldBe ApplicationResult.StorageFailure("timeline")
    }

    @Test
    fun `a linked task prints no title`() {
        task.toString() shouldBe "LinkedTask(id=${task.id}, createdAt=${task.createdAt}, completedAt=null)"
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-30T08:00:00Z")
    }
}
