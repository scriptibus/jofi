// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.paging.PageRequest
import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class TaskSummaryTest {
    private val at = Instant.parse("2026-09-30T10:00:00Z")

    private fun task(
        title: String,
        notes: String? = null,
    ) = Task.create(
        TaskId(UUID.randomUUID()),
        TaskDetails(
            title,
            TaskTiming.Bucket(BucketSpan.DAY, LocalDate.parse("2026-09-30")),
            null,
            notes,
        ),
        TaskOrigin.Manual,
        at,
    )

    @Test
    fun `a summary keeps the excerpt of long notes and flags the cut`() {
        val long = "n".repeat(TextExcerpt.MAX_LENGTH + 40)

        val summary = TaskSummary.of(task("T", long))

        summary.notesExcerpt shouldBe TextExcerpt("n".repeat(TextExcerpt.MAX_LENGTH), true)
        summary.title shouldBe "T"
    }

    @Test
    fun `short notes are kept whole and missing notes stay missing`() {
        TaskSummary.of(task("A", "short")).notesExcerpt shouldBe TextExcerpt("short", false)
        TaskSummary.of(task("B")).notesExcerpt shouldBe null
    }

    @Test
    fun `a page numbers the tasks through the groups in order and keeps every group, empty ones too`() {
        val first = (1..3).map { task("overdue $it") }
        val second = (1..4).map { task("today $it") }
        val groups =
            TaskGroupKind.entries.map {
                TaskGroup(
                    it,
                    when (it) {
                        TaskGroupKind.OVERDUE -> first
                        TaskGroupKind.TODAY -> second
                        else -> emptyList()
                    },
                )
            }

        val page = TaskGroupsPage.of(groups, PageRequest(1, 2))

        page.groups.map { it.kind } shouldContainExactly TaskGroupKind.entries
        page.groups
            .first { it.kind == TaskGroupKind.OVERDUE }
            .tasks
            .map { it.title } shouldContainExactly
            listOf("overdue 3")
        page.groups
            .first { it.kind == TaskGroupKind.TODAY }
            .tasks
            .map { it.title } shouldContainExactly
            listOf("today 1")
        page.info.total shouldBe 7
        page.info.hasMore shouldBe true
    }

    @Test
    fun `paging through the groups reaches every task exactly once`() {
        val tasks = (1..120).map { task("t$it") }
        val groups =
            TaskGroupKind.entries.map { kind ->
                TaskGroup(kind, tasks.filterIndexed { index, _ -> index % TaskGroupKind.entries.size == kind.ordinal })
            }

        val titles =
            (0..2).flatMap { index ->
                TaskGroupsPage.of(groups, PageRequest(index, 50)).groups.flatMap { g -> g.tasks.map { it.title } }
            }

        titles.size shouldBe 120
        titles.toSet().size shouldBe 120
    }
}
