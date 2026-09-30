// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import java.math.BigDecimal
import java.util.Currency

/**
 * A non-negative amount of money in millionths of a currency unit. AI prices are fractions of a
 * cent per token, so cents are too coarse; whole micros keep sums exact without rounding rules.
 */
data class Money(
    val micros: Long,
    val currency: Currency,
) : Comparable<Money> {
    init {
        require(micros >= 0) { "An amount of money must not be negative" }
    }

    /** The amount in currency units, e.g. `0.000150` USD. */
    val amount: BigDecimal get() = BigDecimal.valueOf(micros, MICROS_SCALE)

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(micros, other.micros), currency)
    }

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return micros.compareTo(other.micros)
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) { "Cannot combine $currency and ${other.currency}" }
    }

    companion object {
        private const val MICROS_SCALE = 6

        /**
         * The single accounting currency of AI costs and the budget (ADR-0032): all supported
         * providers price in US dollars, so costs are never converted. The UI may show conversions.
         */
        val ACCOUNTING_CURRENCY: Currency = Currency.getInstance("USD")

        fun zero(currency: Currency): Money = Money(0, currency)

        /** [micros] millionths of a US dollar. */
        fun usd(micros: Long): Money = Money(micros, ACCOUNTING_CURRENCY)
    }

    /** True when this amount is in [ACCOUNTING_CURRENCY]. */
    val isAccountingCurrency: Boolean get() = currency == ACCOUNTING_CURRENCY
}
