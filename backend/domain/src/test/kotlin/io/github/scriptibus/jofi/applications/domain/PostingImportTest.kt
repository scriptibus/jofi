// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PostingImportTest {
    private val text = DescriptionText("Kotlin Developer at ACME, Berlin")
    private val started = PostingImport.start(ImportId(UUID.randomUUID()), text, AT)
    private val application = ApplicationId(UUID.randomUUID())
    private val company = CompanyRef(UUID.randomUUID())

    @Test
    fun `an import is pending, then done with its application (handing on its text) or failed with a reason`() {
        started.status shouldBe ImportStatus.PENDING
        started.attempt shouldBe 1

        val done = started.succeeded(application, LATER)
        done.status shouldBe ImportStatus.SUCCEEDED
        done.application shouldBe application
        done.text shouldBe null
        done.updatedAt shouldBe LATER

        val failed = started.failed(ImportFailure.AI_UNAVAILABLE, LATER)
        failed.failure shouldBe ImportFailure.AI_UNAVAILABLE
        failed.text shouldBe text
    }

    @Test
    fun `only a failed import can be retried, as its next attempt with the kept text`() {
        val failed = started.failed(ImportFailure.UNREADABLE_ANSWER, LATER)

        val retried = failed.retried(LATER.plusSeconds(1)) ?: error("not retried")

        retried shouldBe
            failed.copy(status = ImportStatus.PENDING, failure = null, attempt = 2, updatedAt = LATER.plusSeconds(1))
        started.retried(LATER) shouldBe null
        started.succeeded(application, LATER).retried(LATER) shouldBe null
        shouldThrow<IllegalArgumentException> { failed.failed(ImportFailure.NOT_QUEUED, LATER) }
        shouldThrow<IllegalArgumentException> { failed.succeeded(application, LATER) }
    }

    @Test
    fun `a pending import stalls after the timeout and can then be retried, a fresh one cannot`() {
        val stalledAt = AT.plus(PostingImport.STALLED_AFTER)

        started.stalled(stalledAt.minusSeconds(1)) shouldBe false
        started.retried(stalledAt.minusSeconds(1)) shouldBe null
        started.stalled(stalledAt) shouldBe true
        started.retried(stalledAt) shouldBe started.copy(attempt = 2, updatedAt = stalledAt)
        started.failed(ImportFailure.AI_UNAVAILABLE, AT).stalled(stalledAt) shouldBe false
        started.succeeded(application, AT).retried(stalledAt) shouldBe null
    }

    @Test
    fun `the invariants tie the application, the failure and the text to the status`() {
        shouldThrow<IllegalArgumentException> { started.copy(application = application) }
        shouldThrow<IllegalArgumentException> { started.copy(failure = ImportFailure.NOT_QUEUED) }
        shouldThrow<IllegalArgumentException> { started.copy(text = null) }
        shouldThrow<IllegalArgumentException> { started.copy(attempt = 0) }
        shouldThrow<IllegalArgumentException> { started.copy(updatedAt = AT.minusSeconds(1)) }
    }

    @Test
    fun `its source is pasted text found when the import started, and it never prints the text`() {
        started.toSourceInput() shouldBe SourceInput(SourceKind.MANUAL_CHAT, null, AT, text.value)
        started.toString() shouldNotContain "Kotlin"
    }

    @Test
    fun `the extracted fields become an application input the domain accepts`() {
        val read =
            ExtractedPosting(
                title = " Kotlin Developer ",
                company = "ACME",
                location = "Berlin",
                remoteShare = 40,
                employmentType = EmploymentType.FULL_TIME,
                seniority = Seniority.SENIOR,
                deadline = LocalDate.parse("2026-11-01"),
                postingLanguage = "de",
                payBand =
                    PayBandInput(
                        BigDecimal(60_000),
                        BigDecimal(75_000),
                        "eur",
                        PayPeriod.YEAR,
                        PaySourceKind.POSTING,
                    ),
            )

        val details = (read.checked()?.toInput(company)?.validate() as ApplicationValidation.Valid).value

        details.title shouldBe "Kotlin Developer"
        details.company shouldBe company
        details.remoteShare shouldBe RemoteShare(40)
        details.payBand?.currency shouldBe CurrencyCode("EUR")
        details.payBand?.source shouldBe PaySource.Posting
        details.languageAndTone.postingLanguage shouldBe LanguageTag("de")
    }

    @Test
    fun `broken optional fields are left out, a missing or broken title is no posting`() {
        val read =
            ExtractedPosting(
                title = "Kotlin Developer",
                company = "ACME",
                location = "x".repeat(ApplicationDetails.MAX_LOCATION_LENGTH + 1),
                remoteShare = 150,
                postingLanguage = "not a language",
                payBand = PayBandInput(BigDecimal(9), BigDecimal(1), "EUR", PayPeriod.YEAR, PaySourceKind.POSTING),
                seniority = Seniority.LEAD,
            )

        val input = read.checked()?.toInput(company) ?: error("no input")

        input.location shouldBe null
        input.remoteShare shouldBe null
        input.languageAndTone shouldBe null
        input.payBand shouldBe null
        input.seniority shouldBe Seniority.LEAD
        read.copy(title = null).checked() shouldBe null
        read.copy(title = " ").checked() shouldBe null
        read.copy(company = null).checked() shouldBe null
        read.copy(company = "  ").checked() shouldBe null
        read.copy(title = "x".repeat(ApplicationDetails.MAX_TITLE_LENGTH + 1)).checked() shouldBe null
        read.toString() shouldNotContain "Kotlin"
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-30T12:00:00Z")
        val LATER: Instant = AT.plusSeconds(30)
    }
}
