// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import java.text.Normalizer
import java.util.Locale

/**
 * What two company names must share to name the same company when an import looks for an existing one (#96): the
 * name's letters and digits after Unicode NFKC, ignoring case, spacing and punctuation, without its trailing legal
 * form, so "ACME Robotics GmbH", "Acme-Robotics AG" and "acme robotics" share a key, "ACME Robotics Services" does
 * not. Only the one last legal form goes ("GmbH & Co. KG" counts as one), and words that are also ordinary name words
 * ("Co", "Company", "EV") never go on their own, so "Fast EV GmbH" keeps its "EV". A name that is nothing but a legal
 * form keeps it. Deliberately strict: a wrong match would file a job under another company, while a missed one only
 * adds a company the user can see and delete. Names sharing a key can still be different companies ("Foo AG" and
 * "Foo GmbH"); [folded] tells them apart.
 */
@JvmInline
value class CompanyNameKey private constructor(
    val value: String,
) {
    companion object {
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
        private val WHITESPACE = Regex("\\s+")

        /** Legal forms as their words, longest first, so "GmbH & Co. KG" is stripped as one, never as "KG". */
        private val LEGAL_FORMS: List<List<String>> =
            listOf(
                "gmbh co kgaa",
                "gmbh co kg",
                "ag co kgaa",
                "ag co kg",
                "se co kg",
                "ug haftungsbeschränkt co kg",
                "ug haftungsbeschränkt",
                "co kg",
                "co ltd",
                "e v",
                "e g",
                "s a",
                "gmbh",
                "mbh",
                "gesmbh",
                "ag",
                "se",
                "kg",
                "kgaa",
                "ohg",
                "gbr",
                "ug",
                "eg",
                "inc",
                "llc",
                "llp",
                "ltd",
                "limited",
                "plc",
                "corp",
                "corporation",
                "sa",
                "sas",
                "sarl",
                "srl",
                "spa",
                "bv",
                "nv",
                "ab",
                "oy",
                "as",
                "aps",
            ).map { it.split(' ') }.sortedByDescending { it.size }

        fun of(name: String): CompanyNameKey = CompanyNameKey(coreWords(name).joinToString(""))

        /**
         * The name's words without the trailing legal form, space-separated: what to search the company list for, so
         * a name with another legal form ("ACME AG" for "ACME GmbH") is still among the candidates.
         */
        fun searchText(name: String): String = coreWords(name).joinToString(" ")

        /** The full name as compared exactly: NFC, case folded, spacing collapsed. */
        fun folded(name: String): String =
            Normalizer
                .normalize(name, Normalizer.Form.NFC)
                .lowercase(Locale.ROOT)
                .trim()
                .replace(WHITESPACE, " ")

        private fun coreWords(name: String): List<String> {
            val words =
                Normalizer
                    .normalize(name, Normalizer.Form.NFKC)
                    .lowercase(Locale.ROOT)
                    .split(SEPARATORS)
                    .filter(String::isNotEmpty)
            val form = LEGAL_FORMS.firstOrNull { it.size < words.size && words.takeLast(it.size) == it }
            return if (form == null) words else words.dropLast(form.size)
        }
    }
}
