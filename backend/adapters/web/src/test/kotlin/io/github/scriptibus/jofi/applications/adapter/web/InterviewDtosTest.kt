// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewInput
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class InterviewDtosTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val start = LocalDateTime.parse("2026-10-05T10:00")
    private val interviewUuid = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
    private val applicationUuid = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val first = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val second = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    private val interview =
        Interview(
            InterviewId(interviewUuid),
            ApplicationId(applicationUuid),
            InterviewDetails(
                InterviewType.ON_SITE,
                InterviewTime(Instant.parse("2026-10-05T08:00:00Z"), ZoneId.of("Europe/Berlin")),
                setOf(ContactRef(second), ContactRef(first)),
                "Secret prep",
                "Secret notes",
                InterviewOutcome.REJECTED,
            ),
            2,
            at,
            at.plusSeconds(60),
        )

    @Test
    fun `a request becomes domain input unchanged, validation is the domain's job`() {
        val request =
            InterviewRequest(
                InterviewKind.CASE,
                start,
                " Europe/Berlin ",
                listOf(first, first, second),
                " Prep ",
                " ",
                InterviewResultKind.CANCELLED,
            )

        request.toInput() shouldBe
            InterviewInput(
                InterviewType.CASE,
                start,
                " Europe/Berlin ",
                setOf(ContactRef(first), ContactRef(second)),
                " Prep ",
                " ",
                InterviewOutcome.CANCELLED,
            )
        InterviewRequest(InterviewKind.HR, start, "UTC").toInput() shouldBe
            InterviewInput(InterviewType.HR, start, "UTC")
    }

    @Test
    fun `an interview becomes a response with the instant, the wall-clock time and its zone`() {
        InterviewResponse.from(interview) shouldBe
            InterviewResponse(
                interviewUuid,
                applicationUuid,
                InterviewKind.ON_SITE,
                Instant.parse("2026-10-05T08:00:00Z"),
                start,
                "Europe/Berlin",
                listOf(first, second),
                "Secret prep",
                "Secret notes",
                InterviewResultKind.REJECTED,
                2,
                at,
                at.plusSeconds(60),
            )
        InterviewListResponse
            .from(listOf(interview))
            .interviews
            .single()
            .id shouldBe interviewUuid
        UpcomingInterviewListResponse.from(listOf(UpcomingInterview(interview, "Backend Engineer"))).interviews shouldBe
            listOf(UpcomingInterviewResponse(InterviewResponse.from(interview), "Backend Engineer"))
    }

    @Test
    fun `nothing personal is printed`() {
        val request = InterviewRequest(InterviewKind.HR, start, "UTC", listOf(first), "Secret prep", "Secret notes")
        val response = InterviewResponse.from(interview)

        listOf(request, response, UpcomingInterviewResponse(response, "Secret title")).forEach {
            it.toString() shouldNotContain "Secret"
            it.toString() shouldNotContain first.toString()
        }
    }
}
