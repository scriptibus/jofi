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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class TaskSuggestionRulesTest {
    private val application = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val interview = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    @Test
    fun `one job runs the three rules daily, each rule is its own actor`() {
        TaskSuggestionRules.TYPE shouldBe JobType("task-suggestions")
        TaskSuggestionRules.SCHEDULE.expression shouldBe "30 4 * * *"
        TaskSuggestionRules.SCHEDULE.zone shouldBe ZoneOffset.UTC
        TaskSuggestionRules.SCHEDULE.maxRandomDelay shouldBe Duration.ofMinutes(15)
        TaskSuggestionRules.RULES shouldBe setOf("follow-up", "interview-preparation", "offer-answer")
        FollowUpSuggestion.ACTOR shouldBe Actor.System("follow-up")
        InterviewPreparationSuggestion.ACTOR shouldBe Actor.System("interview-preparation")
        OfferAnswerSuggestion.ACTOR shouldBe Actor.System("offer-answer")
    }

    @Test
    fun `a follow-up is one per silence and due on the UTC day its period ended`() {
        val since = Instant.parse("2026-09-01T10:00:00Z")

        FollowUpSuggestion.origin(application, since) shouldBe
            TaskOrigin.Suggested("follow-up", "application:$application:2026-09-01T10:00:00Z")
        FollowUpSuggestion.origin(application, since.plusSeconds(1)) shouldNotBe
            FollowUpSuggestion.origin(application, since)
        FollowUpSuggestion.details(application, "Backend Engineer", Instant.parse("2026-09-15T23:30:00Z")) shouldBe
            TaskDetails(
                "Follow up: Backend Engineer",
                TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2026, 9, 15)),
                ApplicationRef(application),
            )
    }

    @Test
    fun `the preparation is due the day before on the interview's own calendar, keyed by that day`() {
        // 08:00 on 20 October in Tokyo is still 19 October in UTC.
        val startsAt = Instant.parse("2026-10-19T23:00:00Z")
        val tokyo = ZoneId.of("Asia/Tokyo")

        val day = InterviewPreparationSuggestion.dayBefore(startsAt, tokyo)

        day shouldBe LocalDate.of(2026, 10, 19)
        InterviewPreparationSuggestion.dayBefore(startsAt, ZoneOffset.UTC) shouldBe LocalDate.of(2026, 10, 18)
        InterviewPreparationSuggestion.origin(interview, day) shouldBe
            TaskOrigin.Suggested("interview-preparation", "interview:$interview:2026-10-19")
        InterviewPreparationSuggestion.details(application, "Backend Engineer", day) shouldBe
            TaskDetails(
                "Prepare for the interview: Backend Engineer",
                TaskTiming.Bucket(BucketSpan.DAY, day),
                ApplicationRef(application),
            )
    }

    @Test
    fun `an offer answer is due the day before its date, keyed by the date`() {
        val answerBy = LocalDate.of(2026, 10, 9)

        OfferAnswerSuggestion.origin(application, answerBy) shouldBe
            TaskOrigin.Suggested("offer-answer", "application:$application:2026-10-09")
        OfferAnswerSuggestion.details(application, "Backend Engineer", answerBy).timing shouldBe
            TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2026, 10, 8))
    }

    @Test
    fun `a day outside what a bucket holds is kept at its bounds, and a long title is shortened`() {
        OfferAnswerSuggestion.details(application, "x", LocalDate.of(2000, 1, 1)).timing shouldBe
            TaskTiming.Bucket(BucketSpan.DAY, TaskTiming.EARLIEST_DAY)
        InterviewPreparationSuggestion.details(application, "x", LocalDate.of(2200, 1, 1)).timing shouldBe
            TaskTiming.Bucket(BucketSpan.DAY, LocalDate.of(2099, 12, 31))

        val title = OfferAnswerSuggestion.details(application, "y".repeat(400), LocalDate.of(2026, 10, 9)).title
        title.length shouldBe TaskDetails.MAX_TITLE_LENGTH
        title shouldEndWith "y"
    }
}
