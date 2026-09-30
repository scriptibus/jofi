// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

/**
 * A password in clear text, on its way from the login form to the hasher. [toString] never shows it,
 * so a password that slips into a log line or an error stays hidden (threat model T4, T5). Not a
 * data class on purpose: data classes print their values.
 */
class Password private constructor(
    private val value: String,
) {
    /** The clear text. Call it only where the password is handed to the hasher. */
    fun reveal(): String = value

    override fun equals(other: Any?): Boolean = other is Password && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Password(***)"

    companion object {
        /** NIST SP 800-63B-4 asks for at least 15 characters when a password is the only factor. */
        const val MIN_LENGTH = 15

        /**
         * Upper bound for any submitted password: long enough for passphrases and password managers
         * (NIST asks to allow at least 64), short enough that hashing cannot be abused for load.
         */
        const val MAX_LENGTH = 256

        /** A password as typed at login; `null` when it cannot be a valid password at all. */
        fun submitted(value: String): Password? =
            if (value.isEmpty() ||
                value.length > MAX_LENGTH
            ) {
                null
            } else {
                Password(value)
            }

        /** A new password checked against the policy (first run and password change). */
        fun chosen(value: String): PasswordPolicyCheck =
            when {
                value.codePointCount(0, value.length) < MIN_LENGTH -> PasswordPolicyCheck.TooShort(MIN_LENGTH)
                value.length > MAX_LENGTH -> PasswordPolicyCheck.TooLong(MAX_LENGTH)
                else -> PasswordPolicyCheck.Accepted(Password(value))
            }
    }
}

/** Whether a newly chosen password meets the policy. Failures never carry the password. */
sealed interface PasswordPolicyCheck {
    data class Accepted(
        val password: Password,
    ) : PasswordPolicyCheck

    /** Why a chosen password was rejected. */
    sealed interface Violation : PasswordPolicyCheck

    data class TooShort(
        val minLength: Int,
    ) : Violation

    data class TooLong(
        val maxLength: Int,
    ) : Violation
}

/**
 * The stored form of the password (argon2id encoding with its parameters and salt). Not secret like
 * the password, but still never printed: it would help an offline guessing attack.
 */
class PasswordHash(
    val encoded: String,
) {
    init {
        require(encoded.isNotBlank()) { "A password hash must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is PasswordHash && encoded == other.encoded

    override fun hashCode(): Int = encoded.hashCode()

    override fun toString(): String = "PasswordHash(***)"
}
