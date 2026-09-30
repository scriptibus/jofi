// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.LocalDate
import java.util.Locale

/** A list price in US dollars per million input and output tokens. */
data class TokenPrice(
    val inputPerMillion: BigDecimal,
    val outputPerMillion: BigDecimal,
) {
    init {
        require(inputPerMillion.signum() >= 0 && outputPerMillion.signum() >= 0) { "A price must not be negative" }
    }

    /**
     * What [usage] costs at this price. Dollars per million tokens times tokens is exactly micro
     * dollars, so the only rounding is to whole micros (half up), once per call.
     */
    fun costOf(usage: TokenUsage): Money {
        val micros =
            inputPerMillion.multiply(BigDecimal.valueOf(usage.inputTokens)) +
                outputPerMillion.multiply(BigDecimal.valueOf(usage.outputTokens))
        return Money.usd(micros.setScale(0, RoundingMode.HALF_UP).longValueExact())
    }
}

/** A higher [price] for every token of a call whose prompt has more than [aboveInputTokens] tokens. */
data class LongPromptPrice(
    val aboveInputTokens: Long,
    val price: TokenPrice,
) {
    init {
        require(aboveInputTokens > 0) { "A long-prompt threshold must be positive" }
    }
}

/**
 * The list price of [model] at a provider of [provider] kind, as read from [source] on [checkedOn].
 * Prices are copied from the providers' own pages, never estimated (ADR-0043).
 */
data class ModelPrice(
    val provider: ProviderKind,
    val model: ModelName,
    val base: TokenPrice,
    val longPrompt: LongPromptPrice?,
    val checkedOn: LocalDate,
    val source: URI,
) {
    init {
        // Such an endpoint can serve any model at any price; its prices are the user's (#24).
        require(provider != ProviderKind.OPENAI_COMPATIBLE) { "OpenAI-compatible endpoints have no list price" }
        require(source.scheme == "https" && !source.host.isNullOrBlank()) { "A price needs an https source" }
    }

    fun costOf(usage: TokenUsage): Money {
        val tier = longPrompt?.takeIf { usage.inputTokens > it.aboveInputTokens }?.price ?: base
        return tier.costOf(usage)
    }
}

/**
 * The dated price table of the models Jofi knows (spec §3.2 cost tracking). A model is found by its
 * exact name (ignoring case and Gemini's `models/` prefix): a similar name never borrows a price, so
 * a model missing from the table has an unknown cost instead of a wrong one.
 */
class PriceTable(
    prices: List<ModelPrice>,
) {
    private val byModel: Map<Pair<ProviderKind, String>, ModelPrice> =
        prices.associateBy { it.provider to key(it.model) }

    init {
        require(byModel.size == prices.size) { "A model is priced at most once per provider kind" }
    }

    val prices: Collection<ModelPrice> get() = byModel.values

    fun priceOf(
        provider: ProviderKind,
        model: ModelName,
    ): ModelPrice? = byModel[provider to key(model)]

    /** The estimated cost of [usage], or null when the model has no price or no usage was reported. */
    fun costOf(
        provider: ProviderKind,
        model: ModelName,
        usage: TokenUsage,
    ): Money? = if (usage == TokenUsage.NONE) null else priceOf(provider, model)?.costOf(usage)

    private fun key(model: ModelName): String = model.value.removePrefix("models/").lowercase(Locale.ROOT)

    companion object {
        val EMPTY = PriceTable(emptyList())
    }
}
