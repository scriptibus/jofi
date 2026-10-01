// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.application

import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort
import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort.DueDate
import io.github.scriptibus.jofi.applications.application.port.api.FindCountdownFactsPort.Facts
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.tasks.application.TaskFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.tasks.domain.CountdownKind
import io.github.scriptibus.jofi.tasks.domain.CountdownTarget
import io.github.scriptibus.jofi.tasks.domain.DashboardCountdown
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The dashboard's countdowns (#112): custom ones and those the applications context's API derives. */
class ListDashboardCountdownsUseCaseTest {
    private val fixtures = CountdownFixtures()
    private val facts = mockk<FindCountdownFactsPort>()
    private val dashboard = ListDashboardCountdownsUseCase(fixtures.repository, facts, CLOCK)

    private val berlin = ZoneId.of("Europe/Berlin")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val interview = UUID.randomUUID()
    private val applied = UUID.randomUUID()
    private val offered = UUID.randomUUID()
    private val startsAt = Instant.parse("2026-10-07T01:00:00Z")

    private fun factsAre(found: Facts) {
        every { facts.execute(CLOCK.instant(), any()) } returns found
    }

    @Test
    fun `custom countdowns, the next interview, deadlines and offer answers come together, soonest first`() {
        val custom = fixtures.countdown("Notice ends", "2026-10-06")
        val past = fixtures.countdown("Moved out", "2026-01-01")
        factsAre(
            Facts.Found(
                FindCountdownFactsPort.NextInterview(interview, applied, "Engineer", startsAt, tokyo),
                listOf(DueDate(applied, "Engineer", LocalDate.parse("2026-10-20"))),
                listOf(DueDate(offered, "Architect", LocalDate.parse("2026-10-06"))),
            ),
        )

        val offer = onDay(CountdownKind.OFFER_ANSWER_DEADLINE, "Architect", "2026-10-06", offered)
        val next =
            DashboardCountdown(
                CountdownKind.NEXT_INTERVIEW,
                "Engineer",
                CountdownTarget.At(startsAt, tokyo),
                EntityRef("interview", interview.toString()),
            )
        val deadline = onDay(CountdownKind.APPLICATION_DEADLINE, "Engineer", "2026-10-20", applied)

        dashboard.execute(berlin) shouldBe
            TaskResult.Success(
                listOf(DashboardCountdown.of(past), DashboardCountdown.of(custom), offer, next, deadline),
            )
    }

    @Test
    fun `today is the viewer's, so the derived dates start on their calendar`() {
        factsAre(Facts.Found(null, emptyList(), emptyList()))

        dashboard.execute(berlin) shouldBe TaskResult.Success(emptyList())
        dashboard.execute(tokyo)

        verify { facts.execute(CLOCK.instant(), LocalDate.parse("2026-09-30")) }
        verify { facts.execute(CLOCK.instant(), LocalDate.parse("2026-10-01")) }
    }

    @Test
    fun `facts or countdowns that cannot be read are a storage failure`() {
        factsAre(Facts.Unavailable)
        dashboard.execute(berlin) shouldBe TaskResult.StorageFailure("countdown facts")

        fixtures.failingStore = true
        dashboard.execute(berlin) shouldBe TaskResult.StorageFailure("list")
    }

    private fun onDay(
        kind: CountdownKind,
        title: String,
        date: String,
        application: UUID,
    ) = DashboardCountdown(
        kind,
        title,
        CountdownTarget.OnDay(LocalDate.parse(date)),
        EntityRef("application", application.toString()),
    )
}
