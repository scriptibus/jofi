// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import java.text.Normalizer
import java.util.Locale

/**
 * What two company names must share to name the same company when an import looks for an existing one (#96): the
 * name's letters and digits after Unicode NFKC, ignoring case, spacing and punctuation, without trailing legal forms,
 * so "ACME Robotics GmbH", "Acme-Robotics AG" and "acme robotics" match, "ACME Robotics Services" does not. A name
 * that is nothing but a legal form keeps it. Deliberately strict: a wrong match would file a job under another
 * company, while a missed one only adds a company the user can see and delete.
 */
@JvmInline
value class CompanyNameKey private constructor(
    val value: String,
) {
    companion object {
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        /** Legal forms (and their parts, such as "co" of "GmbH & Co. KG") that end company names. */
        private val LEGAL_FORMS =
            setOf(
                "ag",
                "se",
                "gmbh",
                "mbh",
                "kg",
                "kgaa",
                "ohg",
                "gbr",
                "ug",
                "haftungsbeschränkt",
                "ev",
                "eg",
                "co",
                "inc",
                "llc",
                "llp",
                "ltd",
                "limited",
                "plc",
                "corp",
                "corporation",
                "company",
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
                "gesmbh",
            )

        fun of(name: String): CompanyNameKey = CompanyNameKey(coreWords(name).joinToString(""))

        /**
         * The name's words without trailing legal forms, space-separated: what to search the company list for, so a
         * name with another legal form ("ACME AG" for "ACME GmbH") is still among the candidates.
         */
        fun searchText(name: String): String = coreWords(name).joinToString(" ")

        private fun coreWords(name: String): List<String> {
            val words =
                Normalizer
                    .normalize(name, Normalizer.Form.NFKC)
                    .lowercase(Locale.ROOT)
                    .split(SEPARATORS)
                    .filter(String::isNotEmpty)
            return words.dropLastWhile { it in LEGAL_FORMS }.ifEmpty { words }
        }
    }
}
