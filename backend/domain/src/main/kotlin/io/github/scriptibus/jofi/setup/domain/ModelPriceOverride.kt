// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import java.math.BigDecimal
import java.time.Instant

/**
 * The price the user gives a model of an OpenAI-compatible provider (spec §3.2, ADR-0043, ADR-0055):
 * such endpoints (Ollama, OpenRouter, ...) have no list price, so without an override their calls have
 * an unknown cost. Prices are millionths of a US dollar per million tokens, so 0 (a local model) and
 * fractions of a cent per million are exact. The override belongs to the provider and the model's exact
 * name; it prices calls recorded after it was set and never re-prices the meter.
 */
data class ModelPriceOverride(
    val provider: ProviderId,
    val model: ModelName,
    val inputMicrosPerMillion: Long,
    val outputMicrosPerMillion: Long,
    val updatedAt: Instant,
) {
    init {
        require(inputMicrosPerMillion in PRICE_RANGE && outputMicrosPerMillion in PRICE_RANGE) {
            "A price per million tokens is between 0 and $MAX_MICROS_PER_MILLION micros"
        }
    }

    /** What [usage] costs: micros per million tokens times tokens, rounded half up to whole micros once. */
    fun costOf(usage: TokenUsage): Money =
        TokenPrice(perMillion(inputMicrosPerMillion), perMillion(outputMicrosPerMillion)).costOf(usage)

    private fun perMillion(micros: Long): BigDecimal = BigDecimal.valueOf(micros, MICROS_SCALE)

    companion object {
        /** The most a user can charge per million tokens: 10,000 US dollars; more is a typo. */
        const val MAX_MICROS_PER_MILLION: Long = 10_000_000_000

        private val PRICE_RANGE = 0..MAX_MICROS_PER_MILLION
        private const val MICROS_SCALE = 6

        /** Whether [price] is within the limits the domain accepts. */
        fun isValidPrice(price: Long): Boolean = price in PRICE_RANGE
    }
}

/** A price override as the user entered it, before validation. */
data class ModelPriceInput(
    val model: String,
    val inputMicrosPerMillion: Long?,
    val outputMicrosPerMillion: Long?,
) {
    /** The model and the two prices, or every violation found. */
    fun validate(): SetupValidation<ValidModelPrice> {
        val name = CapabilityInput.modelName(model)
        val violations =
            (name as? SetupValidation.Invalid)?.violations.orEmpty() +
                listOfNotNull(
                    priceViolation(SetupField.INPUT_PRICE, inputMicrosPerMillion),
                    priceViolation(SetupField.OUTPUT_PRICE, outputMicrosPerMillion),
                )
        return when {
            violations.isNotEmpty() -> {
                SetupValidation.Invalid(violations)
            }

            name is SetupValidation.Valid && inputMicrosPerMillion != null && outputMicrosPerMillion != null -> {
                SetupValidation.Valid(ValidModelPrice(name.value, inputMicrosPerMillion, outputMicrosPerMillion))
            }

            else -> {
                SetupValidation.Invalid(listOf(SetupViolation(SetupField.MODEL, SetupViolationKind.REQUIRED)))
            }
        }
    }

    private fun priceViolation(
        field: SetupField,
        micros: Long?,
    ): SetupViolation? =
        when {
            micros == null -> SetupViolation(field, SetupViolationKind.REQUIRED)
            !ModelPriceOverride.isValidPrice(micros) -> SetupViolation(field, SetupViolationKind.OUT_OF_RANGE)
            else -> null
        }
}

/** A validated price: the normalised model name and the prices in micros per million tokens. */
data class ValidModelPrice(
    val model: ModelName,
    val inputMicrosPerMillion: Long,
    val outputMicrosPerMillion: Long,
)
