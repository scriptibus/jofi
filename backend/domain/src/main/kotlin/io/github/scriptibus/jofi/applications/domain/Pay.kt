// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.textProblem
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The pay a posting, a recruiter or an estimate names (spec §6.1): [min] and [max] (at least one, min
 * not above max) in [currency] per [period]. Amounts are **gross** (before tax and deductions) and money
 * with exactly two decimals (see [Amount]), so an amount read back from the database equals the one
 * stored. Pay is personal: [toString] shows no amount.
 */
data class PayBand(
    val min: BigDecimal?,
    val max: BigDecimal?,
    val currency: CurrencyCode,
    val period: PayPeriod,
    val source: PaySource,
) {
    init {
        require(min != null || max != null) { "A pay band names a minimum, a maximum or both" }
        require(listOfNotNull(min, max).all(Amount::isValid)) { "A pay band amount breaks an invariant" }
        require(min == null || max == null || min <= max) { "A pay band's minimum cannot exceed its maximum" }
    }

    override fun toString(): String = "PayBand(currency=$currency, period=$period, source=$source)"
}

/** One gross amount of money in [currency] per [period], e.g. the salary of an offer; [toString] hides it. */
data class Pay(
    val amount: BigDecimal,
    val currency: CurrencyCode,
    val period: PayPeriod,
) {
    init {
        require(Amount.isValid(amount)) { "A pay amount breaks an invariant" }
    }

    override fun toString(): String = "Pay(currency=$currency, period=$period)"
}

/** The rules for amounts of money: 0 to [MAX], stored with exactly [SCALE] decimals. */
object Amount {
    const val SCALE = 2
    val MAX: BigDecimal = BigDecimal("9999999999.99")

    fun isValid(amount: BigDecimal): Boolean = amount.scale() == SCALE && amount.signum() >= 0 && amount <= MAX

    /** What is wrong with [amount] as entered, or `null` if [normalized] accepts it. */
    fun problemOf(amount: BigDecimal): ApplicationProblem? =
        when {
            amount.signum() < 0 || amount > MAX -> ApplicationProblem.OUT_OF_RANGE
            amount.stripTrailingZeros().scale() > SCALE -> ApplicationProblem.TOO_PRECISE
            else -> null
        }

    /** [amount] with exactly [SCALE] decimals; only call it when [problemOf] found nothing. */
    fun normalized(amount: BigDecimal): BigDecimal = amount.setScale(SCALE, RoundingMode.UNNECESSARY)
}

/** An ISO 4217 currency code: three letters A–Z, e.g. `EUR`. */
@JvmInline
value class CurrencyCode(
    val value: String,
) {
    init {
        require(isValid(value)) { "A currency code is three letters A-Z" }
    }

    override fun toString(): String = value

    companion object {
        private val SHAPE = Regex("^[A-Z]{3}$")

        fun isValid(value: String): Boolean = SHAPE.matches(value)
    }
}

enum class PayPeriod { HOUR, DAY, MONTH, YEAR }

/** Where a [PayBand] comes from (spec §6.1). */
sealed interface PaySource {
    /** The posting states it. */
    data object Posting : PaySource

    /** A recruiter or the company told the user. */
    data object Recruiter : PaySource

    /** Estimated (by the AI, M2, or the user) from [basis], e.g. "levels.fyi, Berlin, senior", with [confidence]. */
    data class Estimated(
        val basis: String,
        val confidence: EstimateConfidence,
    ) : PaySource {
        init {
            require(textProblem(basis, MAX_BASIS_LENGTH) == null) { "An estimate basis breaks an invariant" }
        }

        override fun toString(): String = "Estimated(confidence=$confidence)"

        companion object {
            const val MAX_BASIS_LENGTH = 2_000
        }
    }
}

enum class PaySourceKind { POSTING, RECRUITER, ESTIMATED }

enum class EstimateConfidence { LOW, MEDIUM, HIGH }
