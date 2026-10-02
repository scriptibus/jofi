// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DoneTasksTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val open =
        Task.create(
            TaskId(UUID.randomUUID()),
            TaskDetails("Call", TaskTiming.Bucket.SOMEDAY),
            TaskOrigin.Manual,
            at,
        )
    private val done = (open.apply(TaskTransition.COMPLETE, at.plusSeconds(1)) as TaskStateChange.Changed).task

    @Test
    fun `a page holds 1 to the maximum, counted from 0, and the offset skips the earlier pages`() {
        DoneTaskQuery().let { it.page to it.size } shouldBe (0 to DoneTaskQuery.DEFAULT_SIZE)
        DoneTaskQuery(3, 20).offset shouldBe 60
        DoneTaskQuery.of(0, 1) shouldBe DoneTaskQuery(0, 1)
        DoneTaskQuery.of(2, DoneTaskQuery.MAX_SIZE) shouldBe DoneTaskQuery(2, DoneTaskQuery.MAX_SIZE)
        DoneTaskQuery.of(-1, 20) shouldBe null
        DoneTaskQuery.of(0, 0) shouldBe null
        DoneTaskQuery.of(0, DoneTaskQuery.MAX_SIZE + 1) shouldBe null
        shouldThrow<IllegalArgumentException> { DoneTaskQuery(0, DoneTaskQuery.MAX_SIZE + 1) }
        shouldThrow<IllegalArgumentException> { DoneTaskQuery(-1, 1) }
    }

    @Test
    fun `a done page holds done tasks only and a total of at least its size`() {
        DoneTaskPage(listOf(done), 1).total shouldBe 1
        shouldThrow<IllegalArgumentException> { DoneTaskPage(listOf(open), 1) }
        shouldThrow<IllegalArgumentException> { DoneTaskPage(listOf(done), 0) }
    }
}
