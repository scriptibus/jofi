// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.application.port.ApplicationActivityRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationSettingsRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.api.DescribeApplicationEventPort.ApplicationEvent
import io.github.scriptibus.jofi.applications.application.port.api.DescribeApplicationEventPort.Kind
import io.github.scriptibus.jofi.applications.application.port.api.FindGhostedCandidatesPort
import io.github.scriptibus.jofi.applications.application.port.api.FindSuggestionFactsPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStatusChanged
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewDetails
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.applications.domain.InterviewRescheduled
import io.github.scriptibus.jofi.applications.domain.InterviewTime
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.applications.domain.OfferDetails
import io.github.scriptibus.jofi.applications.domain.UpcomingInterview
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.DomainEvent
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The facts of the task suggestions (#95) and the names of the events, which the tasks context asks for. */
class SuggestionFactsUseCasesTest {
    private val fixtures = ApplicationFixtures()
    private val settings = mockk<ApplicationSettingsRepositoryPort>()
    private val activity = mockk<ApplicationActivityRepositoryPort>()
    private val interviews = mockk<InterviewRepositoryPort>()
    private val applications = mockk<ApplicationRepositoryPort>()
    private val facts = FindSuggestionFactsUseCase(settings, activity, interviews, applications)

    private val silentSince = Instant.parse("2026-09-10T08:00:00Z")
    private val applied = fixtures.application()
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val interview =
        Interview
            .log(
                InterviewId(UUID.randomUUID()),
                applied.id,
                InterviewDetails(InterviewType.HR, InterviewTime(Instant.parse("2026-10-19T23:00:00Z"), tokyo)),
                Actor.User,
                NOW,
            ).interview

    private fun offer(answerBy: LocalDate?): Application =
        fixtures.application("Staff Engineer").let {
            it.copy(
                status = ApplicationStatus.OFFER,
                details = it.details.copy(offer = OfferDetails(vacationDays = 30, answerBy = answerBy)),
            )
        }

    private fun storeAnswers(followUpDays: Int = 10) {
        val values = ApplicationSettings.Values(ghostedAfterWeeks = 14, followUpAfterDays = followUpDays)
        every { settings.find() } returns ApplicationStoreResult.Success(ApplicationSettings(values, 1, NOW))
        every { activity.silentSince(any(), any()) } returns
            ApplicationStoreResult.Success(
                listOf(FindGhostedCandidatesPort.Candidate(applied.id.value, "Backend Engineer", silentSince)),
            )
        every { interviews.upcoming(NOW, Interview.MAX_UPCOMING) } returns
            ApplicationStoreResult.Success(listOf(UpcomingInterview(interview, "Backend Engineer")))
    }

    @Test
    fun `applied applications silent for the follow-up days, interviews ahead and offers not yet due are the facts`() {
        storeAnswers()
        val search = slot<ApplicationSearch>()
        val open = offer(LocalDate.of(2026, 9, 30))
        val passed = offer(LocalDate.of(2026, 9, 29))
        val noDate = offer(null)
        every { applications.search(capture(search)) } returns
            ApplicationStoreResult.Success(ApplicationPage(listOf(open, passed, noDate), 3))

        val followUp =
            FindSuggestionFactsPort.FollowUpDue(
                applied.id.value,
                "Backend Engineer",
                silentSince,
                silentSince.plus(TEN_DAYS),
            )
        val ahead =
            FindSuggestionFactsPort.InterviewAhead(
                interview.id.value,
                applied.id.value,
                "Backend Engineer",
                interview.details.time.startsAt,
                tokyo,
            )
        val answer = FindSuggestionFactsPort.OfferOpen(open.id.value, "Staff Engineer", LocalDate.of(2026, 9, 30))

        facts.execute(NOW) shouldBe FindSuggestionFactsPort.Facts.Found(listOf(followUp), listOf(ahead), listOf(answer))
        verify { activity.silentSince(NOW.minus(TEN_DAYS), setOf(ApplicationStatus.APPLIED)) }
        search.captured.statuses shouldBe setOf(ApplicationStatus.OFFER)
        search.captured.size shouldBe ApplicationSearch.MAX_SIZE
    }

    @Test
    fun `any part that cannot be read makes the facts unavailable`() {
        storeAnswers()
        every { applications.search(any()) } returns ApplicationStoreResult.StorageFailure("search")
        facts.execute(NOW) shouldBe FindSuggestionFactsPort.Facts.Unavailable

        every { applications.search(any()) } returns ApplicationStoreResult.Success(ApplicationPage(emptyList(), 0))
        every { interviews.upcoming(any(), any()) } returns ApplicationStoreResult.StorageFailure("upcoming")
        facts.execute(NOW) shouldBe FindSuggestionFactsPort.Facts.Unavailable

        every { settings.find() } returns ApplicationStoreResult.StorageFailure("find")
        facts.execute(NOW) shouldBe FindSuggestionFactsPort.Facts.Unavailable
    }

    @Test
    fun `status changes and interviews logged or rescheduled are named with their application, others are not`() {
        val describe = DescribeApplicationEventUseCase()
        val id = UUID.randomUUID()
        val time = interview.details.time

        describe.execute(
            ApplicationStatusChanged(
                ApplicationId(id),
                ApplicationStatus.SHORTLISTED,
                ApplicationStatus.APPLIED,
                Actor.User,
                NOW,
            ),
        ) shouldBe ApplicationEvent(Kind.STATUS_CHANGED, id)
        describe.execute(
            Interview.log(interview.id, ApplicationId(id), interview.details, Actor.User, NOW).event,
        ) shouldBe ApplicationEvent(Kind.INTERVIEW_SCHEDULED, id)
        describe.execute(
            InterviewRescheduled(interview.id, ApplicationId(id), time, time, Actor.User, NOW),
        ) shouldBe ApplicationEvent(Kind.INTERVIEW_RESCHEDULED, id)
        describe.execute(object : DomainEvent {}) shouldBe null
    }

    private companion object {
        val TEN_DAYS: Duration = Duration.ofDays(10)
    }
}
