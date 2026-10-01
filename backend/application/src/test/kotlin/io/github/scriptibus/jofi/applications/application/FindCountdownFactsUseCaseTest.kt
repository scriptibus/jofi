// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.application.port.ApplicationDueDatesRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort
import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort.DueDate
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The facts of the dashboard countdowns (#112), which the tasks context asks for. */
class FindCountdownFactsUseCaseTest {
    private val interviews = mockk<InterviewRepositoryPort>()
    private val dueDates = mockk<ApplicationDueDatesRepositoryPort>()
    private val facts = FindCountdownFactsUseCase(interviews, dueDates)

    private val today = LocalDate.parse("2026-09-30")
    private val application = ApplicationId(UUID.randomUUID())
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val interview =
        Interview
            .log(
                InterviewId(UUID.randomUUID()),
                application,
                InterviewDetails(InterviewType.HR, InterviewTime(Instant.parse("2026-10-19T23:00:00Z"), tokyo)),
                Actor.User,
                NOW,
            ).interview
    private val deadline = DueDate(UUID.randomUUID(), "Backend Engineer", today.plusDays(3))
    private val answer = DueDate(UUID.randomUUID(), "Staff Engineer", today)
    private val notApplied =
        setOf(ApplicationStatus.DISCOVERED, ApplicationStatus.SHORTLISTED, ApplicationStatus.PREPARING)

    private fun storeAnswers(upcoming: List<UpcomingInterview>) {
        every { interviews.upcoming(NOW, 1) } returns ApplicationStoreResult.Success(upcoming)
        every { dueDates.deadlines(today, notApplied, FindCountdownFactsPort.MAX_PER_KIND) } returns
            ApplicationStoreResult.Success(listOf(deadline))
        every { dueDates.offerAnswers(today, FindCountdownFactsPort.MAX_PER_KIND) } returns
            ApplicationStoreResult.Success(listOf(answer))
    }

    @Test
    fun `the next interview, the deadlines of applications not applied for and the offer answers are the facts`() {
        storeAnswers(listOf(UpcomingInterview(interview, "Backend Engineer")))
        val next =
            FindCountdownFactsPort.NextInterview(
                interview.id.value,
                application.value,
                "Backend Engineer",
                interview.details.time.startsAt,
                tokyo,
            )

        facts.execute(NOW, today) shouldBe FindCountdownFactsPort.Facts.Found(next, listOf(deadline), listOf(answer))
    }

    @Test
    fun `without an interview still to come there is no next interview`() {
        storeAnswers(emptyList())

        facts.execute(NOW, today) shouldBe FindCountdownFactsPort.Facts.Found(null, listOf(deadline), listOf(answer))
    }

    @Test
    fun `any part that cannot be read makes the facts unavailable`() {
        storeAnswers(emptyList())
        every { dueDates.offerAnswers(any(), any()) } returns ApplicationStoreResult.StorageFailure("offerAnswers")
        facts.execute(NOW, today) shouldBe FindCountdownFactsPort.Facts.Unavailable

        storeAnswers(emptyList())
        every { dueDates.deadlines(any(), any(), any()) } returns ApplicationStoreResult.StorageFailure("deadlines")
        facts.execute(NOW, today) shouldBe FindCountdownFactsPort.Facts.Unavailable

        storeAnswers(emptyList())
        every { interviews.upcoming(any(), any()) } returns ApplicationStoreResult.StorageFailure("upcoming")
        facts.execute(NOW, today) shouldBe FindCountdownFactsPort.Facts.Unavailable
    }

    @Test
    fun `facts print no job title`() {
        val next = FindCountdownFactsPort.NextInterview(UUID.randomUUID(), UUID.randomUUID(), "Secret", NOW, tokyo)

        next.toString().contains("Secret") shouldBe false
        deadline.toString().contains("Backend") shouldBe false
    }
}
