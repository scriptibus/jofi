// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PriceTableFileTest {
    private val table = PriceTableFile.load()

    @Test
    fun `every price names its provider page over https and the day it was read`() {
        table.prices.shouldNotBeEmpty()
        table.prices.forEach { price ->
            price.source.scheme shouldBe "https"
            (price.checkedOn <= LocalDate.parse("2026-09-30")) shouldBe true
            OFFICIAL_HOSTS[price.provider] shouldBe price.source.host
        }
        table.prices.map { it.provider }.toSet() shouldBe
            setOf(ProviderKind.ANTHROPIC, ProviderKind.OPENAI, ProviderKind.GEMINI, ProviderKind.MISTRAL)
    }

    @Test
    fun `spot checks against the pricing pages read on 2026-09-30`() {
        table.costOf(ProviderKind.OPENAI, ModelName("gpt-4o-mini"), TokenUsage(1_000_000, 1_000_000)) shouldBe
            Money.usd(750_000)
        table.costOf(ProviderKind.ANTHROPIC, ModelName("claude-haiku-4-5"), TokenUsage(1_000_000, 1_000_000)) shouldBe
            Money.usd(6_000_000)
        table.costOf(ProviderKind.GEMINI, ModelName("gemini-2.5-pro"), TokenUsage(200_001, 0)) shouldBe
            Money.usd(500_003)
        table.costOf(ProviderKind.MISTRAL, ModelName("mistral-small-2603"), TokenUsage(1_000_000, 0)) shouldBe
            Money.usd(150_000)
        table.costOf(ProviderKind.OPENAI_COMPATIBLE, ModelName("gpt-4o-mini"), TokenUsage(1, 1)) shouldBe null
    }

    @Test
    fun `a broken table fails loudly instead of metering with wrong prices`() {
        shouldThrowAny { PriceTableFile.parse("""{"currency":"EUR","prices":[]}""") }
        shouldThrowAny { PriceTableFile.parse("""{"currency":"USD","prices":[{"provider":"OPENAI","models":[]}]}""") }
        shouldThrowAny {
            PriceTableFile.parse(
                """{"currency":"USD","prices":[{"provider":"OPENAI","models":["m"],"inputPerMillion":"x",""" +
                    """"outputPerMillion":"1","checkedOn":"2026-09-30","source":"https://openai.com"}]}""",
            )
        }
    }

    private companion object {
        val OFFICIAL_HOSTS =
            mapOf(
                ProviderKind.ANTHROPIC to "platform.claude.com",
                ProviderKind.OPENAI to "developers.openai.com",
                ProviderKind.GEMINI to "ai.google.dev",
                ProviderKind.MISTRAL to "mistral.ai",
            )
    }
}
