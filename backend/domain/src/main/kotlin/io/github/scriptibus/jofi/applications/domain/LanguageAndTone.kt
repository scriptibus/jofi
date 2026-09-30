// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

/**
 * The language and tone of an application (spec §6.1): the posting's language (detected in M2, or
 * entered), the language the user applies in, the form of address and the tone. Every generated
 * document, message and interview training follows them. A null [applicationLanguage] follows the
 * posting's, so a later detection changes both until the user chooses.
 */
data class LanguageAndTone(
    val postingLanguage: LanguageTag? = null,
    val applicationLanguage: LanguageTag? = null,
    val formOfAddress: FormOfAddress? = null,
    val tone: Tone? = null,
) {
    /** The language to apply in: the one the user chose, else the posting's. */
    val effectiveApplicationLanguage: LanguageTag? get() = applicationLanguage ?: postingLanguage

    companion object {
        /** Nothing detected or chosen yet. */
        val UNKNOWN = LanguageAndTone()
    }
}

/**
 * A BCP 47 language tag as entered, e.g. `de`, `en` or `de-CH`: a two- or three-letter language and
 * optional subtags of one to eight letters or digits, at most [MAX_LENGTH] characters.
 */
@JvmInline
value class LanguageTag(
    val value: String,
) {
    init {
        require(isValid(value)) { "A language tag is a BCP 47 tag such as de or en-GB" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 35
        private val SHAPE = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$")

        fun isValid(value: String): Boolean = value.length <= MAX_LENGTH && SHAPE.matches(value)
    }
}

/** How the application addresses the reader. */
enum class FormOfAddress {
    /** German informal "Du". */
    DU,

    /** German formal "Sie". */
    SIE,

    /** Neither, e.g. English. */
    NEUTRAL,
}

enum class Tone { PERSONAL, PROFESSIONAL }
