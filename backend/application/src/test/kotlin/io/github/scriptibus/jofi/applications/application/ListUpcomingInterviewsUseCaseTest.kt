// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.applications.application.ApplicationFixtures.Companion.NOW
import io.github.scriptibus.jofi.applications.application.port.InterviewRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
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
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

class ListUpcomingInterviewsUseCaseTest {
    private val interviews = mockk<InterviewRepositoryPort>()
    private val upcoming = ListUpcomingInterviewsUseCase(interviews, CLOCK)

    private val soon =
        UpcomingInterview(
            Interview
                .log(
                    InterviewId(UUID.randomUUID()),
                    ApplicationId(UUID.randomUUID()),
                    InterviewDetails(
                        InterviewType.TECHNICAL,
                        InterviewTime(NOW.plus(Duration.ofDays(2)), ZoneId.of("Asia/Tokyo")),
                    ),
                    Actor.User,
                    NOW,
                ).interview,
            "Backend Engineer",
        )

    @Test
    fun `lists the interviews from the current stored instant on, at most the upcoming limit`() {
        every { interviews.upcoming(NOW, Interview.MAX_UPCOMING) } returns ApplicationStoreResult.Success(listOf(soon))

        upcoming.execute() shouldBe ApplicationResult.Success(listOf(soon))
        verify(exactly = 1) { interviews.upcoming(NOW, Interview.MAX_UPCOMING) }
    }

    @Test
    fun `a storage failure is a result, not an exception`() {
        every { interviews.upcoming(any(), any()) } returns ApplicationStoreResult.StorageFailure("upcoming interviews")

        upcoming.execute() shouldBe ApplicationResult.StorageFailure("upcoming interviews")
    }
}
