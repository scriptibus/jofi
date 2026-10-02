// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.TextExcerpt
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class InterviewSummaryTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val contact = ContactRef(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))

    private fun interview(
        preparation: String?,
        notes: String?,
    ) = Interview(
        InterviewId(UUID.randomUUID()),
        ApplicationId(UUID.randomUUID()),
        InterviewDetails(
            InterviewType.PHONE_SCREEN,
            InterviewTime(Instant.parse("2026-10-05T08:00:00Z"), ZoneId.of("Europe/Berlin")),
            setOf(contact),
            preparation,
            notes,
            InterviewOutcome.PASSED,
        ),
        3,
        at,
        at,
    )

    @Test
    fun `both notes become excerpts, cut and flagged when long, the rest is kept`() {
        val long = "p".repeat(TextExcerpt.MAX_LENGTH + 1)
        val source = interview(long, "short")

        val summary = InterviewSummary.of(source)

        summary.preparationNotesExcerpt shouldBe TextExcerpt("p".repeat(TextExcerpt.MAX_LENGTH), true)
        summary.notesExcerpt shouldBe TextExcerpt("short", false)
        summary.version shouldBe 3
        summary.participants shouldBe setOf(contact)
        summary.outcome shouldBe InterviewOutcome.PASSED
        summary.time shouldBe source.details.time
    }

    @Test
    fun `missing notes stay missing and the text form shows no notes`() {
        val summary = InterviewSummary.of(interview(null, "secret words"))

        summary.preparationNotesExcerpt shouldBe null
        summary.toString() shouldNotContain "secret"
    }

    @Test
    fun `a summary can be cut from other notes than the interview's own`() {
        val summary = InterviewSummary.of(interview("p 0170 1234567", "n 0170 1234567"), "p [withheld]", "n [withheld]")

        summary.preparationNotesExcerpt shouldBe TextExcerpt("p [withheld]", false)
        summary.notesExcerpt shouldBe TextExcerpt("n [withheld]", false)
    }
}
