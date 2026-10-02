// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ModelPriceOverrideTest {
    private val provider = ProviderId(UUID.fromString("00000000-0000-0000-0000-000000000009"))
    private val at = Instant.parse("2026-10-02T08:00:00Z")
    private val max = ModelPriceOverride.MAX_MICROS_PER_MILLION

    private fun override(
        input: Long,
        output: Long,
    ) = ModelPriceOverride(provider, ModelName("llama3.1:8b"), input, output, at)

    @Test
    fun `a call costs the price per million tokens times its tokens in whole micros`() {
        // 0.15 USD in, 0.60 USD out per million tokens: 1,000 in and 500 out cost 150 + 300 micros.
        override(150_000, 600_000).costOf(TokenUsage(1_000, 500)) shouldBe Money.usd(450)
    }

    @Test
    fun `a free local model costs zero, which is a known cost`() {
        override(0, 0).costOf(TokenUsage(1_000_000, 1_000_000)) shouldBe Money.usd(0)
    }

    @Test
    fun `the cost is rounded half up once per call`() {
        // 1 micro per million: 500,000 tokens cost exactly 0.5 micros, rounded up to 1.
        override(1, 0).costOf(TokenUsage(500_000, 0)) shouldBe Money.usd(1)
        override(1, 0).costOf(TokenUsage(499_999, 0)) shouldBe Money.usd(0)
    }

    @Test
    fun `the highest price at the highest usage does not overflow`() {
        override(max, max).costOf(TokenUsage(1_000_000_000, 1_000_000_000)) shouldBe Money.usd(20_000_000_000_000)
    }

    @Test
    fun `a price outside zero to the maximum is refused`() {
        override(0, max)
        shouldThrow<IllegalArgumentException> { override(-1, 0) }
        shouldThrow<IllegalArgumentException> { override(0, max + 1) }
    }

    @Test
    fun `input validates to a normalised model and the prices`() {
        val valid = ModelPriceInput("  llama3.1:8b ", 0, max).validate()

        valid shouldBe SetupValidation.Valid(ValidModelPrice(ModelName("llama3.1:8b"), 0, max))
    }

    @Test
    fun `input names every violation with its field`() {
        val invalid = ModelPriceInput(" ", -1, max + 1).validate()

        invalid shouldBe
            SetupValidation.Invalid(
                listOf(
                    SetupViolation(SetupField.MODEL, SetupViolationKind.REQUIRED),
                    SetupViolation(SetupField.INPUT_PRICE, SetupViolationKind.OUT_OF_RANGE),
                    SetupViolation(SetupField.OUTPUT_PRICE, SetupViolationKind.OUT_OF_RANGE),
                ),
            )
    }

    @Test
    fun `a missing price is required and a too long model name is too long`() {
        val tooLong = "m".repeat(CapabilityInput.MAX_MODEL_NAME + 1)

        ModelPriceInput("gpt", null, 1).validate() shouldBe
            SetupValidation.Invalid(listOf(SetupViolation(SetupField.INPUT_PRICE, SetupViolationKind.REQUIRED)))
        ModelPriceInput(tooLong, 1, null).validate() shouldBe
            SetupValidation.Invalid(
                listOf(
                    SetupViolation(SetupField.MODEL, SetupViolationKind.TOO_LONG),
                    SetupViolation(SetupField.OUTPUT_PRICE, SetupViolationKind.REQUIRED),
                ),
            )
    }

    @Test
    fun `the limits themselves are valid`() {
        ModelPriceInput("m".repeat(CapabilityInput.MAX_MODEL_NAME), 0, max).validate() shouldBe
            SetupValidation.Valid(
                ValidModelPrice(ModelName("m".repeat(CapabilityInput.MAX_MODEL_NAME)), 0, max),
            )
    }
}
