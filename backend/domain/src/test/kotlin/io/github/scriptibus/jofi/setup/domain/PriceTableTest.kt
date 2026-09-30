// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class PriceTableTest {
    private val source = URI("https://example.com/pricing")
    private val day = LocalDate.parse("2026-09-30")

    private fun price(
        kind: ProviderKind,
        model: String,
        input: String,
        output: String,
        longPrompt: LongPromptPrice? = null,
    ) = ModelPrice(kind, ModelName(model), TokenPrice(BigDecimal(input), BigDecimal(output)), longPrompt, day, source)

    private val table =
        PriceTable(
            listOf(
                price(ProviderKind.OPENAI, "gpt-4o-mini", "0.15", "0.60"),
                price(
                    ProviderKind.GEMINI,
                    "gemini-2.5-pro",
                    "1.25",
                    "10.00",
                    LongPromptPrice(200_000, TokenPrice(BigDecimal("2.50"), BigDecimal("15.00"))),
                ),
            ),
        )

    @Test
    fun `dollars per million tokens times tokens is micro dollars, rounded half up once`() {
        // 1,234 * 0.15 + 567 * 0.60 = 185.1 + 340.2 = 525.3 micros
        table.costOf(ProviderKind.OPENAI, ModelName("gpt-4o-mini"), TokenUsage(1_234, 567)) shouldBe Money.usd(525)
        // 3 * 0.15 = 0.45, 1 * 0.60 = 0.60 -> 1.05 -> 1 micro
        table.costOf(ProviderKind.OPENAI, ModelName("gpt-4o-mini"), TokenUsage(3, 1)) shouldBe Money.usd(1)
        table.costOf(ProviderKind.OPENAI, ModelName("gpt-4o-mini"), TokenUsage(1_000_000, 0)) shouldBe
            Money.usd(150_000)
    }

    @Test
    fun `a long prompt is priced at the higher tier for all its tokens`() {
        val model = ModelName("models/Gemini-2.5-Pro")

        table.costOf(ProviderKind.GEMINI, model, TokenUsage(200_000, 1_000)) shouldBe Money.usd(260_000)
        table.costOf(ProviderKind.GEMINI, model, TokenUsage(200_001, 1_000)) shouldBe Money.usd(515_003)
    }

    @Test
    fun `an unknown model, another provider kind or missing usage has an unknown cost`() {
        table.costOf(ProviderKind.OPENAI, ModelName("gpt-4o"), TokenUsage(10, 10)) shouldBe null
        table.costOf(ProviderKind.OPENAI, ModelName("gpt-4o-mini-2024-07-18"), TokenUsage(10, 10)) shouldBe null
        table.costOf(ProviderKind.MISTRAL, ModelName("gpt-4o-mini"), TokenUsage(10, 10)) shouldBe null
        table.costOf(ProviderKind.OPENAI, ModelName("gpt-4o-mini"), TokenUsage.NONE) shouldBe null
        PriceTable.EMPTY.costOf(ProviderKind.OPENAI, ModelName("gpt-4o-mini"), TokenUsage(1, 1)) shouldBe null
    }

    @Test
    fun `prices are non-negative, sourced over https, once per model and never for compatible endpoints`() {
        shouldThrow<IllegalArgumentException> { TokenPrice(BigDecimal("-0.01"), BigDecimal.ZERO) }
        shouldThrow<IllegalArgumentException> { LongPromptPrice(0, TokenPrice(BigDecimal.ONE, BigDecimal.ONE)) }
        shouldThrow<IllegalArgumentException> { price(ProviderKind.OPENAI_COMPATIBLE, "llama3", "0", "0") }
        shouldThrow<IllegalArgumentException> {
            ModelPrice(
                ProviderKind.OPENAI,
                ModelName("x"),
                TokenPrice(BigDecimal.ONE, BigDecimal.ONE),
                null,
                day,
                URI("http://example.com"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            PriceTable(
                listOf(price(ProviderKind.OPENAI, "gpt-5", "1", "1"), price(ProviderKind.OPENAI, "GPT-5", "2", "2")),
            )
        }
    }

    @Test
    fun `a billing month runs from the first to the first in UTC`() {
        val month = BillingMonth.of(Instant.parse("2026-09-30T23:59:59Z"))

        month shouldBe BillingMonth(YearMonth.of(2026, 9))
        month.start shouldBe Instant.parse("2026-09-01T00:00:00Z")
        month.end shouldBe Instant.parse("2026-10-01T00:00:00Z")
        BillingMonth.of(Instant.parse("2026-10-01T00:00:00Z")) shouldBe BillingMonth(YearMonth.of(2026, 10))
        BillingMonth.of(Instant.parse("2026-12-31T12:00:00Z")).end shouldBe Instant.parse("2027-01-01T00:00:00Z")
    }

    @Test
    fun `a cost entry may have an unknown cost`() {
        val entry =
            CostEntry(
                task = io.github.scriptibus.jofi.shared.domain.ai.AiTask.CHAT,
                provider = ProviderId(java.util.UUID.randomUUID()),
                providerKind = ProviderKind.OPENAI_COMPATIBLE,
                model = ModelName("llama3"),
                usage = TokenUsage(1, 1),
                estimatedCost = null,
                occurredAt = Instant.EPOCH,
            )

        entry.estimatedCost shouldBe null
    }
}
