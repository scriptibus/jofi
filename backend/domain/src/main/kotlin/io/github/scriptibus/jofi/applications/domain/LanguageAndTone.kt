// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import java.util.Locale

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
 * A BCP 47 language tag, e.g. `de`, `en` or `de-CH`: a two- or three-letter language and optional
 * subtags of one to eight letters or digits, at most [MAX_LENGTH] characters. Input is brought into
 * [canonical] case; the invariant accepts any case, as the database does.
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

        /**
         * [value] (a valid tag) in the case BCP 47 recommends, so `DE-ch` and `de-CH` are the same tag:
         * the language and everything else lower-case, a four-letter script title-case (`Hant`) and a
         * two-letter region upper-case (`CH`), each only in its place right after the language (or
         * script); subtags after a one-letter singleton (extensions, `x-` private use) stay lower-case.
         * Tags are ASCII, so `Locale.ROOT` makes the case mapping locale-independent.
         */
        fun canonical(value: String): String {
            val subtags = value.lowercase(Locale.ROOT).split('-')
            val firstSingleton = subtags.drop(1).indexOfFirst { it.length == 1 }
            val end = if (firstSingleton < 0) subtags.size else firstSingleton + 1
            val hasScript = subtags.getOrNull(1)?.let { isLetters(it, SCRIPT_LENGTH) } == true
            val regionAt = if (hasScript) 2 else 1
            return subtags
                .mapIndexed { index, subtag ->
                    when {
                        index >= end -> subtag
                        index == 1 && hasScript -> subtag.replaceFirstChar { it.titlecase(Locale.ROOT) }
                        index == regionAt && isLetters(subtag, REGION_LENGTH) -> subtag.uppercase(Locale.ROOT)
                        else -> subtag
                    }
                }.joinToString("-")
        }

        private fun isLetters(
            subtag: String,
            length: Int,
        ): Boolean = subtag.length == length && subtag.all(Char::isLetter)

        private const val SCRIPT_LENGTH = 4
        private const val REGION_LENGTH = 2
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
