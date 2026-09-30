// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.domain

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.job.JobType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class GhostedSuggestionTest {
    private val application = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    @Test
    fun `the rule names the job, its schedule and the actor`() {
        GhostedSuggestion.TYPE shouldBe JobType("ghosted-suggestion")
        GhostedSuggestion.ACTOR shouldBe Actor.System("ghosted-suggestion")
        GhostedSuggestion.SCHEDULE.expression shouldBe "0 4 * * *"
        GhostedSuggestion.SCHEDULE.zone shouldBe ZoneOffset.UTC
        GhostedSuggestion.SCHEDULE.maxRandomDelay shouldBe Duration.ofMinutes(15)
    }

    @Test
    fun `a silence is identified by its application and the activity it started with`() {
        val since = Instant.parse("2026-06-01T10:00:00.123456Z")

        GhostedSuggestion.origin(application, since) shouldBe
            TaskOrigin.Suggested("ghosted-suggestion", "application:$application:2026-06-01T10:00:00.123456Z")
        GhostedSuggestion.origin(application, since.plusSeconds(1)) shouldNotBe
            GhostedSuggestion.origin(application, since)
    }

    @Test
    fun `the suggested task is linked to the application and has no due date`() {
        GhostedSuggestion.details(application, "Backend Engineer") shouldBe
            TaskDetails("Mark as Ghosted: Backend Engineer", TaskTiming.Bucket.SOMEDAY, ApplicationRef(application))
    }

    @Test
    fun `a long title is shortened to fit, never inside a surrogate pair or before a space`() {
        val longest = "x".repeat(TaskDetails.MAX_TITLE_LENGTH)
        GhostedSuggestion.details(application, longest).title.length shouldBe TaskDetails.MAX_TITLE_LENGTH

        val room = TaskDetails.MAX_TITLE_LENGTH - "Mark as Ghosted: ".length
        val splitPair = "a".repeat(room - 1) + "😀" + "tail"
        GhostedSuggestion.details(application, splitPair).title shouldEndWith "a"

        val endsInSpace = "b".repeat(room - 1) + " tail"
        GhostedSuggestion.details(application, endsInSpace).title shouldEndWith "b"
    }
}
